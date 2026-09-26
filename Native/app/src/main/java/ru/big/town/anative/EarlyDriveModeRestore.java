package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.Map;

/** Early drive-profile-only repair. All provider/Binder work stays off lifecycle/event threads. */
final class EarlyDriveModeRestore {
    private static final String TAG = "EarlyDriveRestore";
    private static final EarlyDriveModeRestore INSTANCE = new EarlyDriveModeRestore();
    private static final OemVehicleStateTransport.StateKey DRIVE_STATE =
            new OemVehicleStateTransport.StateKey("DRIVING_MODE_SET", 545);

    static final class Settings {
        final String mode;
        final boolean enabled;
        final boolean debug;

        Settings(String mode, boolean enabled, boolean debug) {
            this.mode = mode;
            this.enabled = enabled;
            this.debug = debug;
        }
    }

    private final EarlyDriveModeRestorePolicy policy = new EarlyDriveModeRestorePolicy();
    private Handler worker;

    private EarlyDriveModeRestore() {}

    static void activate(long generation, String reason) {
        INSTANCE.start(generation, reason);
    }

    static void stop(long generation, String reason) {
        INSTANCE.cancel(generation, reason);
    }

    private synchronized void start(long generation, String reason) {
        Context context = GlobalVars.SAVE_CONTEXT;
        if (context == null || !policy.activate(generation)) return;
        if (worker == null) {
            HandlerThread thread = new HandlerThread("EarlyDriveRestore");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        worker.removeCallbacksAndMessages(null);
        Log.i(TAG, "start gen=" + generation + " reason=" + reason);
        worker.post(() -> poll(context.getApplicationContext(), generation));
    }

    private synchronized void cancel(long generation, String reason) {
        boolean wasActive = policy.isActive(generation, SystemClock.elapsedRealtime());
        if (!policy.stop(generation)) return;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (wasActive) Log.i(TAG, "stop gen=" + generation + " reason=" + reason);
    }

    private boolean current(long generation) {
        return policy.isActive(generation, SystemClock.elapsedRealtime());
    }

    private void poll(Context context, long generation) {
        long startedAt = SystemClock.elapsedRealtime();
        if (!policy.beginCheck(generation, startedAt)) return;
        try {
            // Read only the drive target/debug switch, without loadModes' unrelated side effects.
            Settings settings = MainActivity.readEarlyDriveRestoreSettings(context);
            if (!current(generation)) return;
            if (settings == null) return; // Retry missing settings within the same fixed window.
            if (!policy.acceptTarget(generation, SystemClock.elapsedRealtime(),
                    settings.enabled, settings.mode)) {
                Log.i(TAG, "stop: drive restore disabled, invalid or selection changed");
                return;
            }
            CanSender.setDebugMode(settings.debug);
            Map<OemVehicleStateTransport.StateKey, Integer> snapshot =
                    OemVehicleStateTransport.readVehicleStates(
                            context, Collections.singleton(DRIVE_STATE));
            Integer observed = snapshot == null ? null : snapshot.get(DRIVE_STATE);
            EarlyDriveModeRestorePolicy.Decision decision = policy.observe(
                    generation, SystemClock.elapsedRealtime(), observed);
            Log.i(TAG, "check gen=" + generation + " observed=" + observed
                    + " target=" + settings.mode + " decision=" + decision);
            if (decision == EarlyDriveModeRestorePolicy.Decision.RESTORE_ECO) {
                boolean accepted = CanSender.runGuardedSend(() -> current(generation),
                        () -> DriveModeCanTransport.dispatch(context, settings.mode).accepted());
                // Acceptance is not physical confirmation and never extends the 30-second window.
                Log.i(TAG, "Eco repair accepted=" + accepted + " target=" + settings.mode);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "early check failed: " + e.getMessage());
        } finally {
            scheduleNext(context, generation, startedAt);
        }
    }

    private synchronized void scheduleNext(Context context, long generation, long startedAt) {
        long delay = policy.nextDelay(generation, SystemClock.elapsedRealtime(), startedAt);
        if (delay >= 0L) worker.postDelayed(() -> poll(context, generation), delay);
    }
}
