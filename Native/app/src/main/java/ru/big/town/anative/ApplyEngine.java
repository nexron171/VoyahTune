package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Applies one saved snapshot per eligible door/first-Drive trigger, or explicit Apply request. */
public final class ApplyEngine {
    static final String TAG = "$$$ ApplyEngine $$$";

    /** Exactly-once terminal state for an automated action tied to a physical wake. */
    public enum WakeActionResult {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private static volatile Handler bg;
    private static volatile Handler commandBg;

    // Only cancellation and feedback state use this lock; Binder/CAN calls run outside it.
    private static final Object RESTORE_LOCK = new Object();
    private static final RestoreRunState RESTORE_RUN_STATE = new RestoreRunState();
    private static final ModeSyncPolicy MODE_SYNC_POLICY = new ModeSyncPolicy();

    private static long beginRestoreGate(String reason) {
        long generation = MODE_SYNC_POLICY.beginRestore();
        Log.i(TAG, "mode sync gate CLOSED gen=" + generation + " reason=" + reason);
        return generation;
    }

    /** Засыпание/потеря CAN: внешний feedback закрыт до следующего успешного применения. */
    public static void resetRestoreGate() {
        resetRestoreGate("external reset");
    }

    public static void resetRestoreGate(String reason) {
        final long gateGeneration;
        final long runGeneration;
        synchronized (RESTORE_LOCK) {
            runGeneration = RESTORE_RUN_STATE.cancelAndAdvance();
            gateGeneration = MODE_SYNC_POLICY.freeze();
        }
        Log.i(TAG, "mode sync gate FROZEN gen=" + gateGeneration
                + " runGen=" + runGeneration + " reason=" + reason);
    }

    /** Snapshot used by finite event adjudication which must fail closed across physical sleep. */
    static long capturePhysicalWakeGeneration() {
        synchronized (RESTORE_LOCK) {
            return RESTORE_RUN_STATE.currentGeneration();
        }
    }

    /** True only while the exact captured physical-wake lifetime is still active. */
    static boolean isPhysicalWakeGenerationActive(long candidate) {
        synchronized (RESTORE_LOCK) {
            return RESTORE_RUN_STATE.isActionAllowed(candidate);
        }
    }

    /** Полный снимок источника истины, прочитанный MainActivity из provider/cache. */
    static void noteLoadedModes(String drive, String energy, String recycle,
                                boolean driveEnabled, boolean energyEnabled,
                                boolean recycleEnabled,
                                boolean driveRememberLast, boolean energyRememberLast,
                                boolean recycleRememberLast) {
        MODE_SYNC_POLICY.updateExpected(
                drive, energy, recycle,
                driveEnabled, energyEnabled, recycleEnabled,
                driveRememberLast, energyRememberLast, recycleRememberLast);
    }

    /** Явно сохранённый режим (руль или уже разрешённая внешняя смена) сразу становится ожидаемым. */
    static void noteSavedMode(boolean energy, String mode) {
        noteSavedMode(energy ? "energy" : "driveMode", mode);
    }

    static void noteSavedMode(String modeKey, String mode) {
        MODE_SYNC_POLICY.updateExpectedMode(modeKey, mode);
    }

    /** Немедленно закрывает/открывает feedback persistence для одного режима. */
    static void noteRememberLastMode(String modeKey, boolean rememberLast) {
        MODE_SYNC_POLICY.updateRememberLast(modeKey, rememberLast);
    }

    static void noteDriverDoorOpened() {
        synchronized (RESTORE_LOCK) {
            MODE_SYNC_POLICY.onDriverDoorOpened();
        }
    }

    static void noteGear(int gear) {
        synchronized (RESTORE_LOCK) {
            MODE_SYNC_POLICY.onGear(gear);
        }
    }

    static boolean canRememberModeSelection() {
        synchronized (RESTORE_LOCK) {
            return MODE_SYNC_POLICY.canRememberSelection();
        }
    }

    /** Feedback never initiates another restore. */
    static boolean shouldPersistModeFeedback(String modeKey, String observedMode) {
        synchronized (RESTORE_LOCK) {
            return MODE_SYNC_POLICY.evaluate(modeKey, observedMode) == ModeSyncPolicy.Decision.ACCEPT;
        }
    }

    static String currentVehicleMode(String modeKey, String fallback) {
        return MODE_SYNC_POLICY.currentMode(modeKey, fallback);
    }

    static void noteVehicleMode(String modeKey, String mode) {
        MODE_SYNC_POLICY.observe(modeKey, mode);
    }

    /** Revalidates stable feedback without holding the restore-cancellation lock across Binder I/O. */
    static void persistModeFeedbackIfAllowed(Context context, boolean energy, String observedMode) {
        persistModeFeedbackIfAllowed(
                context, energy ? "energy" : "driveMode", observedMode);
    }

    static void persistModeFeedbackIfAllowed(
            Context context, String modeKey, String observedMode) {
        final long gateGeneration;
        synchronized (RESTORE_LOCK) {
            if (!shouldPersistModeFeedback(modeKey, observedMode)) return;
            gateGeneration = MODE_SYNC_POLICY.currentGeneration();
        }

        if (MainActivity.isLoadedMode(modeKey, observedMode)) return;

        // Provider.update/broadcast may block on another process. Revalidate immediately before it,
        // then release RESTORE_LOCK so sleep can cancel CAN even if that external process is stuck.
        synchronized (RESTORE_LOCK) {
            if (!MODE_SYNC_POLICY.canPersist(
                    gateGeneration, modeKey)) {
                return;
            }
        }
        MainActivity.persistSavedMode(context, modeKey, observedMode);
    }

    private ApplyEngine() {}

    private static synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("ApplyEngine");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    private static synchronized Handler commandBg() {
        if (commandBg == null) {
            HandlerThread t = new HandlerThread("CanCommands");
            t.start();
            commandBg = new Handler(t.getLooper());
        }
        return commandBg;
    }

    /** Wake side effects remain active independently of mode restoration. */
    public static void activateWake(String reason) {
        synchronized (RESTORE_LOCK) {
            RESTORE_RUN_STATE.activate(RESTORE_RUN_STATE.currentGeneration());
            MODE_SYNC_POLICY.activateWake();
        }
        Log.i(TAG, "wake active: " + reason);
    }

    /** Each event queues its own immediate pass, including events arriving during another pass. */
    public static void scheduleApply(String reason) {
        enqueueApply(reason, false, null);
    }

    /** Explicit Apply supersedes older queued restores and always notifies the client. */
    public static void applyNow(Runnable onDone) {
        enqueueApply("manual apply", true, onDone);
    }

    private static void enqueueApply(String reason, boolean manual, Runnable onDone) {
        final Handler h = bg();
        synchronized (RESTORE_LOCK) {
            final long wakeGeneration = RESTORE_RUN_STATE.currentGeneration();
            RESTORE_RUN_STATE.activate(wakeGeneration);
            final long restoreEpoch = manual ? RESTORE_RUN_STATE.cancelRestoreAndAdvance()
                    : RESTORE_RUN_STATE.currentRestoreEpoch();
            final long gateGeneration = beginRestoreGate(reason);
            h.post(() -> applyInternal(onDone, gateGeneration, wakeGeneration, restoreEpoch));
        }
    }

    /** Automated action which must be cancelled if its physical wake has already ended. */
    public static void postWakeAction(String reason, Runnable action) {
        postWakeAction(reason, (BooleanSupplier) () -> {
            action.run();
            return true;
        }, null);
    }

    /**
     * Automated CAN action tied to the current awake generation. It uses a small command worker,
     * so light/battery reactions use the same serialized CAN transport. CanSender keeps
     * transactions atomic. Sleep cancels it before each actual frame.
     */
    public static void postWakeAction(String reason, Runnable action, Runnable onSkipped) {
        postWakeAction(reason, (BooleanSupplier) () -> {
            action.run();
            return true;
        }, result -> {
            if (result != WakeActionResult.SUCCESS && onSkipped != null) onSkipped.run();
        });
    }

    /**
     * Automated action with one terminal callback. The callback is delivered exactly once for
     * success, CAN/application failure, cancellation, a frozen wake, or an exception.
     */
    public static void postWakeAction(String reason, BooleanSupplier action,
                                      Consumer<WakeActionResult> onComplete) {
        Log.i(TAG, "postWakeAction: " + reason);
        final Handler h = commandBg();
        final long runGeneration;
        synchronized (RESTORE_LOCK) {
            runGeneration = RESTORE_RUN_STATE.currentGeneration();
            if (!RESTORE_RUN_STATE.isActionAllowed(runGeneration)) {
                Log.i(TAG, "postWakeAction [" + reason + "] suppressed while frozen");
                if (!h.post(() -> deliverWakeActionResult(reason, onComplete,
                        WakeActionResult.SKIPPED))) {
                    deliverWakeActionResult(reason, onComplete, WakeActionResult.SKIPPED);
                }
                return;
            }
        }
        if (!h.post(() -> runWakeActionExactlyOnce(reason,
                () -> RESTORE_RUN_STATE.isActionAllowed(runGeneration), action, onComplete))) {
            deliverWakeActionResult(reason, onComplete, WakeActionResult.SKIPPED);
        }
    }

    /** Android-free core used by unit tests and by the command HandlerThread. */
    static void runWakeActionExactlyOnce(String reason, BooleanSupplier guard,
                                         BooleanSupplier action,
                                         Consumer<WakeActionResult> onComplete) {
        final boolean[] entered = {false};
        WakeActionResult result;
        try {
            boolean successful = CanSender.runGuardedSend(guard, () -> {
                entered[0] = true;
                return action.getAsBoolean();
            });
            if (!entered[0]) {
                result = WakeActionResult.SKIPPED;
            } else if (successful) {
                // Once every requested frame was sent, a later sleep must not rewrite SUCCESS as
                // SKIPPED and produce a second, contradictory terminal callback in the caller.
                result = WakeActionResult.SUCCESS;
            } else {
                result = guardAllowed(guard)
                        ? WakeActionResult.FAILED : WakeActionResult.SKIPPED;
            }
        } catch (Throwable t) {
            result = WakeActionResult.FAILED;
            safeWakeActionError("postWakeAction [" + reason + "] failed: "
                    + t.getMessage(), t);
        }
        deliverWakeActionResult(reason, onComplete, result);
    }

    private static boolean guardAllowed(BooleanSupplier guard) {
        try {
            return guard == null || guard.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deliverWakeActionResult(String reason,
                                                Consumer<WakeActionResult> onComplete,
                                                WakeActionResult result) {
        if (onComplete == null) return;
        try {
            onComplete.accept(result);
        } catch (Throwable t) {
            // Terminal delivery already happened; logging must never turn it into a retry or hide
            // the original action result (and Android's local-test Log stub may itself throw).
            safeWakeActionError(
                    "postWakeAction [" + reason + "] terminal callback failed", t);
        }
    }

    private static void safeWakeActionError(String message, Throwable error) {
        try {
            Log.e(TAG, message, error);
        } catch (Throwable ignored) {
            // Logging is diagnostic only; exactly-once terminal semantics take precedence.
        }
    }

    /** Explicit user command: prompt and never discarded merely because SCREEN_OFF raced the tap. */
    public static void postUserCommand(String reason, Runnable action) {
        postUserCommand(reason, action, null);
    }

    /** Explicit user command with an exactly-once terminal callback, including queue rejection. */
    public static void postUserCommand(String reason, Runnable action, Runnable onTerminal) {
        Log.i(TAG, "postUserCommand: " + reason);
        final Handler commandHandler = commandBg();
        final long restoreEpoch;
        final long gateGeneration;
        synchronized (RESTORE_LOCK) {
            // An explicit command wins over every already queued/running automatic restore, but it
            // is not a new physical wake. Keeping wake generation intact means unrelated automated
            // wake actions retain their correct sleep cancellation token.
            restoreEpoch = RESTORE_RUN_STATE.cancelRestoreAndAdvance();
            gateGeneration = MODE_SYNC_POLICY.cancelRestore();
        }
        Log.i(TAG, "automatic restore cancelled for user command: restoreEpoch="
                + restoreEpoch + " gateGen=" + gateGeneration + " reason=" + reason);
        enqueueUserCommand(commandHandler, reason, action, () -> {
            try {
                final boolean opened;
                synchronized (RESTORE_LOCK) {
                    opened = MODE_SYNC_POLICY.completeUserCommand(gateGeneration);
                }
                if (opened) {
                    Log.i(TAG, "user command feedback gate OPEN gen=" + gateGeneration);
                }
            } finally {
                if (onTerminal != null) onTerminal.run();
            }
        });
    }

    /** Explicit command unrelated to restored drive modes (for example battery preheating). */
    public static void postIndependentUserCommand(String reason, Runnable action) {
        postIndependentUserCommand(reason, action, null);
    }

    /** Independent explicit command with exactly-once completion, including queue rejection. */
    public static void postIndependentUserCommand(String reason, Runnable action,
                                                  Runnable onTerminal) {
        Log.i(TAG, "postIndependentUserCommand: " + reason);
        enqueueUserCommand(commandBg(), reason, action, onTerminal);
    }

    private static void enqueueUserCommand(Handler commandHandler, String reason, Runnable action,
                                           Runnable onTerminal) {
        if (!commandHandler.post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.e(TAG, "postUserCommand [" + reason + "] failed: " + t.getMessage(), t);
            } finally {
                if (onTerminal != null) onTerminal.run();
            }
        })) {
            Log.e(TAG, "postUserCommand [" + reason + "] was not queued");
            if (onTerminal != null) onTerminal.run();
        }
    }

    private static void applyInternal(Runnable onDone, long gateGeneration,
                                      long wakeGeneration, long restoreEpoch) {
        CycleResult result = CycleResult.FAILED;
        try {
            result = runCycle(wakeGeneration, restoreEpoch);
        } catch (Throwable t) {
            Log.e(TAG, "runCycle failed: " + t.getMessage(), t);
        } finally {
            synchronized (RESTORE_LOCK) {
                if (RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                    if (result.completesRestore()) MODE_SYNC_POLICY.completeRestore(gateGeneration);
                    else MODE_SYNC_POLICY.failRestore(gateGeneration);
                }
            }
            Log.i(TAG, "restore result=" + result + " gen=" + gateGeneration);
            if (onDone != null) onDone.run();
        }
    }

    private static CycleResult runCycle(long wakeGeneration, long restoreEpoch) {
        BooleanSupplier current =
                () -> RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch);
        if (!current.getAsBoolean()) return CycleResult.CANCELLED;
        Context ctx = GlobalVars.SAVE_CONTEXT;
        if (ctx == null) return CycleResult.FAILED;
        // Read once, using the last complete cache immediately if the provider is unavailable.
        int status = MainActivity.loadModes(ctx, true);
        if (!current.getAsBoolean()) return CycleResult.CANCELLED;
        if (status == 0) {
            Log.w(TAG, "no saved settings; skipping this restore event");
            return CycleResult.FAILED;
        }
        final CanRestorePlan plan = MainActivity.createCanRestorePlan();
        final CanRestorePlan.AttemptResult[] result = {
                CanRestorePlan.AttemptResult.TRANSIENT_FAILURE
        };
        boolean accepted = CanSender.runGuardedSend(current, () -> {
            result[0] = plan.sendPending(
                    (frames, label) -> MainActivity.setCanValues(1, frames, label));
            return result[0].isComplete();
        });
        if (!current.getAsBoolean()) return CycleResult.CANCELLED;
        if (!accepted) return CycleResult.FAILED;

        byte[][] customFrames;
        try {
            customFrames = validCanFrames(MainActivity.getCustomCommand());
        } catch (RuntimeException e) {
            customFrames = new byte[0][];
            Log.w(TAG, "invalid custom CAN command ignored: " + e.getMessage());
        }
        final byte[][] frames = customFrames;
        // Preserve the user's configured custom-command count, without restore retry delays.
        for (int i = 0; frames.length > 0 && i < MainActivity.customCommandCount; i++) {
            if (!current.getAsBoolean()) return CycleResult.CANCELLED;
            CanSender.runGuardedSend(current,
                    () -> MainActivity.setCanValues(1, frames, "custom command (unlock/wake)"));
        }
        if (!current.getAsBoolean()) return CycleResult.CANCELLED;
        return result[0] == CanRestorePlan.AttemptResult.ACCEPTED_UNCONFIRMED
                ? CycleResult.ACCEPTED_UNCONFIRMED : CycleResult.SUCCESS;
    }

    /** Оставляет только реальные CAN frames; штатный кадр этого протокола всегда ровно 10 bytes. */
    static byte[][] validCanFrames(byte[][] frames) {
        if (frames == null || frames.length == 0) return new byte[0][];
        int validCount = 0;
        for (byte[] frame : frames) {
            if (frame != null && frame.length == 10) validCount++;
        }
        if (validCount == 0) return new byte[0][];
        if (validCount == frames.length) return frames;

        byte[][] valid = new byte[validCount][];
        int target = 0;
        for (byte[] frame : frames) {
            if (frame != null && frame.length == 10) valid[target++] = frame;
        }
        return valid;
    }

    private enum CycleResult {
        SUCCESS,
        ACCEPTED_UNCONFIRMED,
        FAILED,
        CANCELLED;

        boolean completesRestore() {
            return this == SUCCESS || this == ACCEPTED_UNCONFIRMED;
        }
    }

    /** Cancellation only: door and Drive requests never cover or deduplicate one another. */
    static final class RestoreRunState {
        private long generation;
        private long restoreEpoch;
        private boolean active;

        synchronized long currentGeneration() { return generation; }
        synchronized long currentRestoreEpoch() { return restoreEpoch; }
        synchronized boolean isCurrent(long candidate) { return candidate == generation; }
        synchronized boolean isRestoreCurrent(long wakeCandidate, long restoreCandidate) {
            return wakeCandidate == generation && restoreCandidate == restoreEpoch;
        }
        synchronized boolean activate(long candidate) {
            if (candidate != generation) return false;
            active = true;
            return true;
        }
        synchronized boolean isActionAllowed(long candidate) {
            return active && candidate == generation;
        }
        synchronized long cancelRestoreAndAdvance() { return ++restoreEpoch; }
        synchronized long cancelAndAdvance() {
            generation++;
            restoreEpoch++;
            active = false;
            return generation;
        }
    }
}
