package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Централизованный «движок» применения настроек вождения (drive/energy/recycle/…).
 *
 * <p>Заменяет прежний {@code SetModesService.worker(...)} и решает проблему «после пробуждения
 * настройки не применяются, пока не нажмёшь Применить». Ключевые свойства:</p>
 *
 * <ul>
 *   <li><b>Два контролируемых worker-а.</b> Долгий wake-retry не задерживает явную команду
 *       пользователя; фактические CAN batch/transactions атомарно сериализует {@link CanSender}.</li>
 *   <li><b>Дебаунс + коалесинг.</b> Пачка событий пробуждения (несколько power-состояний
 *       подряд, SCREEN_ON и т.п.) сворачивается в один цикл — {@link #scheduleApply(String)}.
 *       Триггер, пришедший во время уже идущего цикла, считается «покрытым» им и не
 *       порождает второй цикл (см. {@link RestoreRunState}).</li>
 *   <li><b>Ожидание готовности настроек.</b> Настройки читаются из ContentProvider соседнего
 *       приложения, которое на раннем пробуждении может быть ещё не поднято. Движок повторяет
 *       чтение с паузами: первые {@link #PROVIDER_ONLY_ATTEMPTS} попыток принимаются только
 *       свежие данные провайдера, дальше соглашаемся и на локальный кэш
 *       (см. {@link MainActivity#loadModes(Context, boolean)}).</li>
 *   <li><b>Многократная отправка.</b> Команды шлются несколько раз (устойчивость к тому, что
 *       автомобиль может сбросить режим в первые секунды после пробуждения).</li>
 * </ul>
 */
public final class ApplyEngine {
    static final String TAG = "$$$ ApplyEngine $$$";

    /** Exactly-once terminal state for an automated action tied to a physical wake. */
    public enum WakeActionResult {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    // Дебаунс пачки триггеров пробуждения.
    private static final long DEBOUNCE_MS = 800;
    // Ожидание готовности настроек: до READY_MAX_ATTEMPTS попыток с паузой READY_RETRY_MS.
    // Первые PROVIDER_ONLY_ATTEMPTS попыток кэш не принимаем — даём провайдеру шанс подняться,
    // чтобы не применить устаревший снимок, когда свежие данные вот-вот будут доступны.
    private static final int  READY_MAX_ATTEMPTS     = 8;
    private static final int  PROVIDER_ONLY_ATTEMPTS = 2;
    private static final long READY_RETRY_MS         = 2500;
    // Параметры wake-цикла: НУЖНО набрать WAKE_REPEAT УСПЕШНЫХ проходов (авто может сбрасывать режим,
    // пока его системы поднимаются после пробуждения) с паузой WAKE_PAUSE между отправками.
    // 3 успешных прохода с паузой 5 с: отправили → 5 с → отправили → 5 с → отправили.
    private static final int  WAKE_REPEAT = 3;
    private static final long WAKE_PAUSE  = 5000;
    // На пробуждении CAN-сервис/HAL поднимается не сразу — первые проходы падают (res=-1). Ждём готовности
    // CAN до этого дедлайна (первого успешного прохода), иначе фикс. окно заканчивалось ДО готовности CAN
    // и режим не применялся («нестабильно»).
    private static final long WAKE_CAN_DEADLINE_MS = 120_000;

    private static volatile Handler bg;
    private static volatile Handler commandBg;
    // Токен для дедупликации отложенного дебаунс-раннабла.
    private static final Object DEBOUNCE_TOKEN = new Object();

    // Все переходы generation/coverage и mode-gate упорядочены этим lock. Сам CAN-цикл lock не держит:
    // он только часто проверяет cooperative cancellation token через RESTORE_RUN_STATE.
    private static final Object RESTORE_LOCK = new Object();
    private static final RestoreRunState RESTORE_RUN_STATE = new RestoreRunState();

    // Boolean «CAN один раз успешно отправлен» оказался недостаточен: OEM способен ещё раз выставить
    // дефолт после успешной команды. Policy держит источник истины закрытым на restore+settle, различает
    // поколения конкурирующих wake-триггеров и просит корректирующий цикл при несовпадении feedback.
    private static final ModeSyncPolicy MODE_SYNC_POLICY = new ModeSyncPolicy();

    private static long beginRestoreGate(String reason) {
        long generation = MODE_SYNC_POLICY.beginRestore();
        Log.i(TAG, "mode sync gate CLOSED gen=" + generation + " reason=" + reason);
        return generation;
    }

    /** Засыпание/потеря CAN: внешний feedback закрыт до следующего успешного restore+settle. */
    public static void resetRestoreGate() {
        resetRestoreGate("external reset");
    }

    public static void resetRestoreGate(String reason) {
        final long gateGeneration;
        final long runGeneration;
        synchronized (RESTORE_LOCK) {
            // Сначала инвалидируем выполняющийся цикл, затем закрываем gate. Completion старого цикла
            // проверяет тот же lock и потому уже не сможет записать coverage или открыть gate.
            runGeneration = RESTORE_RUN_STATE.cancelAndAdvance();
            gateGeneration = MODE_SYNC_POLICY.freeze();

            // Не создаём HandlerThread только ради cancel. Если он уже есть, удаляем pending debounce;
            // выполняющийся runnable остановится сам на ближайшей cooperative-проверке.
            Handler h = bg;
            if (h != null) h.removeCallbacksAndMessages(DEBOUNCE_TOKEN);
        }
        Log.i(TAG, "mode sync gate FROZEN gen=" + gateGeneration
                + " runGen=" + runGeneration + " reason=" + reason);
    }

    /** Полный снимок источника истины, прочитанный MainActivity из provider/cache. */
    static void noteLoadedModes(String drive, String energy,
                                boolean driveEnabled, boolean energyEnabled) {
        MODE_SYNC_POLICY.updateExpected(drive, energy, driveEnabled, energyEnabled);
    }

    /** Явно сохранённый режим (руль или уже разрешённая внешняя смена) сразу становится ожидаемым. */
    static void noteSavedMode(boolean energy, String mode) {
        MODE_SYNC_POLICY.updateExpectedMode(energy, mode);
    }

    /**
     * Решение для VehicleState: во время wake feedback только подтверждает/оспаривает restore и никогда
     * не перезаписывает provider. Несовпадение запускает коалесцированный корректирующий цикл.
     */
    static boolean shouldPersistModeFeedback(boolean energy, String observedMode) {
        final ModeSyncPolicy.Decision decision;
        synchronized (RESTORE_LOCK) {
            decision = MODE_SYNC_POLICY.evaluate(energy, observedMode, SystemClock.uptimeMillis());
            if (decision == ModeSyncPolicy.Decision.CORRECT) {
                String kind = energy ? "energy" : "drive";
                Log.w(TAG, "wake feedback conflicts with saved " + kind + " mode: " + observedMode
                        + " — restoring source of truth again");
                // Keep evaluation and enqueue ordered against resetRestoreGate(). Otherwise sleep
                // could freeze/cancel between them and this pre-sleep feedback would enqueue a fresh
                // restore after reset. Java synchronized is reentrant, so scheduleApply uses the same
                // lock and reset either happens wholly before or wholly after this correction enqueue.
                scheduleApply("wake " + kind + " drift " + observedMode);
                return false;
            }
        }
        if (decision == ModeSyncPolicy.Decision.IGNORE) return false;
        return true;
    }

    /** Revalidates stable feedback without holding the restore-cancellation lock across Binder I/O. */
    static void persistModeFeedbackIfAllowed(Context context, boolean energy, String observedMode) {
        final long gateGeneration;
        synchronized (RESTORE_LOCK) {
            if (!shouldPersistModeFeedback(energy, observedMode)) return;
            gateGeneration = MODE_SYNC_POLICY.currentGeneration();
        }

        if (MainActivity.isLoadedMode(energy, observedMode)) return;

        // Provider.update/broadcast may block on another process. Revalidate immediately before it,
        // then release RESTORE_LOCK so sleep can cancel CAN even if that external process is stuck.
        synchronized (RESTORE_LOCK) {
            if (!MODE_SYNC_POLICY.canPersist(
                    gateGeneration, SystemClock.uptimeMillis())) {
                return;
            }
        }
        MainActivity.persistSavedMode(context, energy, observedMode);
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

    /**
     * Дебаунс-триггер применения (boot, power-состояния, SCREEN_ON…). Несколько вызовов подряд
     * сворачиваются в один цикл; триггеры, пришедшие во время идущего цикла, им же и покрыты.
     */
    public static void scheduleApply(String reason) {
        final Handler h = bg();
        final long gateGeneration;
        final long wakeGeneration;
        final long restoreEpoch;
        final long triggerSequence;
        final long scheduledAt;
        Log.i(TAG, "scheduleApply: " + reason);
        synchronized (RESTORE_LOCK) {
            // Закрываем синхронно и ДО дебаунса: WAIT_FOR_VHAL/CanBus reconnect должны опередить
            // первый VehicleState с системным ECO. Sleep/reset использует этот же lock, поэтому он
            // либо удалит поставленный runnable, либо schedule уже относится к следующему wake.
            wakeGeneration = RESTORE_RUN_STATE.currentGeneration();
            RESTORE_RUN_STATE.activate(wakeGeneration);
            restoreEpoch = RESTORE_RUN_STATE.currentRestoreEpoch();
            gateGeneration = beginRestoreGate("schedule: " + reason);
            triggerSequence = RESTORE_RUN_STATE.registerTrigger(wakeGeneration, restoreEpoch);
            scheduledAt = SystemClock.uptimeMillis();
            h.removeCallbacksAndMessages(DEBOUNCE_TOKEN);
            h.postAtTime(() -> {
                if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                    Log.i(TAG, "apply debounce cancelled/superseded: " + reason);
                    return;
                }

                RestoreRunState.Coverage coverage;
                synchronized (RESTORE_LOCK) {
                    coverage = RESTORE_RUN_STATE.coverage(
                            wakeGeneration, restoreEpoch, triggerSequence);
                    if (coverage.kind == RestoreRunState.Coverage.SUCCESS) {
                        if (!MODE_SYNC_POLICY.completeRestore(
                                gateGeneration, coverage.completedAt)) {
                            Log.i(TAG, "covered cycle belongs to an older gate generation; gate stays closed");
                        }
                    } else if (coverage.kind == RestoreRunState.Coverage.FAILED) {
                        MODE_SYNC_POLICY.failRestore(gateGeneration);
                    }
                }
                if (coverage.kind == RestoreRunState.Coverage.SUCCESS) {
                    Log.i(TAG, "apply skipped (covered by successful previous cycle): " + reason);
                    return;
                }
                if (coverage.kind == RestoreRunState.Coverage.FAILED) {
                    // Триггер уже попал внутрь закончившегося 120-секундного окна. Немедленный второй
                    // полный цикл лишь удвоит нагрузку; gate остаётся закрыт. Новый late reconnect,
                    // зарегистрированный ПОСЛЕ completion, получит новый sequence и запустит retry.
                    Log.i(TAG, "apply skipped (covered by failed cycle; waiting for a new trigger): "
                            + reason);
                    return;
                }
                applyInternal(WAKE_REPEAT, WAKE_PAUSE, null, gateGeneration,
                        wakeGeneration, restoreEpoch);
            }, DEBOUNCE_TOKEN, scheduledAt + DEBOUNCE_MS);
        }
    }

    /**
     * Немедленное применение (кнопка «Применить»). Выполняется всегда (без skip-логики —
     * пользователь попросил явно). {@code onDone} вызывается по завершении цикла — для
     * разблокировки кнопки на клиенте.
     */
    public static void applyNow(int repeat, long pause, Runnable onDone) {
        final Handler h = bg();
        synchronized (RESTORE_LOCK) {
            // Явное нажатие не должно 120с ждать за автоматическим retry. Инвалидируем его;
            // cooperative/per-frame guard остановит старый цикл, затем этот runnable пойдёт следующим.
            // Physical wake generation не трогаем: свет/дворники в том же wake остаются valid.
            final long wakeGeneration = RESTORE_RUN_STATE.currentGeneration();
            RESTORE_RUN_STATE.activate(wakeGeneration);
            final long restoreEpoch = RESTORE_RUN_STATE.cancelRestoreAndAdvance();
            final long gateGeneration = beginRestoreGate("manual apply");
            h.removeCallbacksAndMessages(DEBOUNCE_TOKEN);
            h.post(() -> applyInternal(repeat, pause, onDone, gateGeneration,
                    wakeGeneration, restoreEpoch));
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
     * so a 120-second restore retry cannot starve light/battery reactions; CanSender still keeps
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
            Handler restoreHandler = bg;
            if (restoreHandler != null) {
                restoreHandler.removeCallbacksAndMessages(DEBOUNCE_TOKEN);
            }
        }
        Log.i(TAG, "automatic restore cancelled for user command: restoreEpoch="
                + restoreEpoch + " gateGen=" + gateGeneration + " reason=" + reason);
        enqueueUserCommand(commandHandler, reason, action, () -> {
            final boolean settling;
            synchronized (RESTORE_LOCK) {
                settling = MODE_SYNC_POLICY.completeUserCommand(
                        gateGeneration, SystemClock.uptimeMillis());
            }
            if (settling) {
                Log.i(TAG, "user command gate SETTLING gen=" + gateGeneration + " for "
                        + ModeSyncPolicy.POST_RESTORE_SETTLE_MS + "ms");
            }
        });
    }

    /** Explicit command unrelated to restored drive modes (for example battery preheating). */
    public static void postIndependentUserCommand(String reason, Runnable action) {
        Log.i(TAG, "postIndependentUserCommand: " + reason);
        enqueueUserCommand(commandBg(), reason, action, null);
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

    // Выполняется строго на bg-потоке — сериализация даёт single-flight без флагов/локов.
    private static void applyInternal(int repeat, long pause, Runnable onDone,
                                      long gateGeneration, long wakeGeneration,
                                      long restoreEpoch) {
        CycleResult result = CycleResult.FAILED;
        try {
            result = runCycle(repeat, pause, wakeGeneration, restoreEpoch);
        } catch (Throwable t) {
            Log.e(TAG, "runCycle failed: " + t.getMessage(), t);
        } finally {
            long endedAt = SystemClock.uptimeMillis();
            boolean completionAccepted = false;
            boolean gateSettling = false;
            synchronized (RESTORE_LOCK) {
                // Повторная проверка закрывает race cancel между последней отправкой/ожиданием и
                // completion. Устаревший цикл не считается coverage даже если раньше успел в CAN.
                if (result != CycleResult.CANCELLED) {
                    completionAccepted = RESTORE_RUN_STATE.completeCycle(
                            wakeGeneration, restoreEpoch,
                            result == CycleResult.SUCCESS, endedAt);
                    if (completionAccepted && result == CycleResult.SUCCESS) {
                        gateSettling = MODE_SYNC_POLICY.completeRestore(gateGeneration, endedAt);
                    } else if (completionAccepted && result == CycleResult.FAILED) {
                        MODE_SYNC_POLICY.failRestore(gateGeneration);
                    }
                }
            }
            if (!completionAccepted) {
                Log.i(TAG, "restore wakeGen=" + wakeGeneration + " epoch=" + restoreEpoch
                        + " cancelled/stale; coverage and gate unchanged");
            } else if (result == CycleResult.SUCCESS && gateSettling) {
                Log.i(TAG, "mode sync gate SETTLING gen=" + gateGeneration + " for "
                        + ModeSyncPolicy.POST_RESTORE_SETTLE_MS + "ms");
            } else if (result == CycleResult.SUCCESS) {
                Log.i(TAG, "restore gate generation " + gateGeneration
                        + " was superseded; gate stays closed");
            } else {
                Log.w(TAG, "restore wakeGen=" + wakeGeneration + " epoch=" + restoreEpoch
                        + " failed; gate stays closed until a new wake/reconnect trigger");
            }
            if (onDone != null) onDone.run();
        }
    }

    private static CycleResult runCycle(int repeat, long pause, long wakeGeneration,
                                        long restoreEpoch) {
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
            return CycleResult.CANCELLED;
        }

        Context ctx = GlobalVars.SAVE_CONTEXT;
        if (ctx == null) {
            Log.w(TAG, "runCycle: no context, skip");
            return CycleResult.FAILED;
        }

        // 1) Дожидаемся готовности настроек (2=провайдер, 1=кэш, 0=нет данных).
        int status = 0;
        for (int attempt = 1; attempt <= READY_MAX_ATTEMPTS; attempt++) {
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
            boolean allowCache = attempt > PROVIDER_ONLY_ATTEMPTS;
            status = MainActivity.loadModes(ctx, allowCache);
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
            if (status > 0) break;
            Log.w(TAG, "runCycle: settings not ready (" + attempt + "/" + READY_MAX_ATTEMPTS
                    + (allowCache ? ", cache empty too" : ", provider-only phase") + ")");
            if (attempt < READY_MAX_ATTEMPTS
                    && !waitWhileCurrent(READY_RETRY_MS, wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
        }
        if (status == 0) {
            Log.e(TAG, "runCycle: no settings (provider+cache empty) — nothing to apply");
            return CycleResult.FAILED;
        }

        // 2) Отправляем ПОКА не наберём `repeat` УСПЕШНЫХ проходов (CAN готов) ИЛИ не выйдет дедлайн.
        //    Раньше было фикс. `repeat` проходов независимо от результата: на пробуждении CAN-сервис/HAL
        //    ещё поднимался, все проходы падали (res=-1) в отведённые ~28 с → режим не применялся. Теперь
        //    неуспешные проходы (CAN не готов) НЕ засчитываются и мы ждём его готовности до дедлайна, а
        //    после первого успеха добиваем `repeat` успешных для устойчивости к сбросу режима авто.
        // elapsedRealtime включает deep sleep: если freeze/sleep callback был пропущен прошивкой,
        // 120-секундный предел всё равно не растянется на всё время стоянки.
        long nowElapsed = SystemClock.elapsedRealtime();
        long deadline = WAKE_CAN_DEADLINE_MS > Long.MAX_VALUE - nowElapsed
                ? Long.MAX_VALUE : nowElapsed + WAKE_CAN_DEADLINE_MS;
        int okPasses = 0, tries = 0;
        long lastSuccessfulPassCoverage = -1L;
        while (true) {
            // Повторно проверяем ДО следующей отправки: elapsedRealtime мог перескочить дедлайн,
            // пока процесс был в deep sleep без доставленного freeze callback.
            if (tries > 0 && SystemClock.elapsedRealtime() >= deadline) break;
            // Check immediately before and after every CAN pass. An in-flight native call cannot be
            // revoked safely, but cancellation prevents all subsequent sends and stale completion.
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
            // Snapshot the trigger boundary BEFORE sending. A wake signal that arrives while this
            // pass is in flight (or immediately after it) must not be declared covered by CAN that
            // had already started; it will enqueue one conservative follow-up cycle instead.
            long passCoverage = RESTORE_RUN_STATE.coverageBoundary(
                    wakeGeneration, restoreEpoch);
            if (passCoverage < 0L) return CycleResult.CANCELLED;
            boolean ok = CanSender.runGuardedSend(
                    () -> RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch),
                    MainActivity::runCmds);
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
            tries++;
            if (ok) {
                okPasses++;
                lastSuccessfulPassCoverage = passCoverage;
            } else {
                Log.w(TAG, "runCycle: CAN не готов, проход " + tries + " не прошёл (успешных=" + okPasses + ")");
            }
            if (okPasses >= repeat) break;                             // набрали нужное число успешных
            if (SystemClock.elapsedRealtime() >= deadline) break;      // CAN так и не поднялся вовремя
            if (!waitWhileCurrent(pause, wakeGeneration, restoreEpoch)) {
                return CycleResult.CANCELLED;
            }
        }
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
            return CycleResult.CANCELLED;
        }
        if (okPasses == 0) {
            Log.e(TAG, "runCycle: CAN не поднялся за " + (WAKE_CAN_DEADLINE_MS / 1000) + "с — режим НЕ применён");
        } else if (okPasses < repeat) {
            Log.w(TAG, "runCycle: применено частично — успешных проходов " + okPasses + "/" + repeat + " (tries=" + tries + ")");
        } else {
            Log.i(TAG, "runCycle: режим применён, успешных проходов " + okPasses + "/" + repeat + " (tries=" + tries + ")");
        }

        // Custom-команды могут разблокировать/разбудить узлы автомобиля. Не исполняем их, если
        // основной CAN restore не сделал ни одного успешного прохода или wake уже отменён сном.
        if (okPasses == 0) return CycleResult.FAILED;
        synchronized (RESTORE_LOCK) {
            // Coverage заканчивается здесь, сразу после последнего успешного mode-CAN pass. Wake
            // trigger, пришедший позже во время custom/trailing wait, нельзя считать исправленным
            // этим циклом: режимы к тому моменту уже были отправлены.
            if (!RESTORE_RUN_STATE.markCanRestoreComplete(
                    wakeGeneration, restoreEpoch, lastSuccessfulPassCoverage)) {
                return CycleResult.CANCELLED;
            }
        }

        // 3) Кастомные команды пользователя (unlock/wake и т.п.). Пустая настройка исторически
        // представлена как {{}} при default customCommandCount=1. Фильтруем её заранее, иначе даже
        // без команды цикл делал лишнюю отправку и trailing-паузу 5 с на каждое пробуждение.
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
            return CycleResult.CANCELLED;
        }
        byte[][] parsedCustomFrames;
        try {
            parsedCustomFrames = validCanFrames(MainActivity.getCustomCommand());
        } catch (RuntimeException e) {
            // A malformed optional custom string must not turn an already successful mode restore
            // into FAILED and provoke another correction/retry window.
            parsedCustomFrames = new byte[0][];
            Log.e(TAG, "invalid custom CAN command ignored: " + e.getMessage());
        }
        final byte[][] customFrames = parsedCustomFrames;
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
            return CycleResult.CANCELLED;
        }
        if (customFrames.length > 0) {
            for (int i = 0; i < MainActivity.customCommandCount; i++) {
                if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                    return CycleResult.CANCELLED;
                }
                CanSender.runGuardedSend(
                        () -> RESTORE_RUN_STATE.isRestoreCurrent(
                                wakeGeneration, restoreEpoch),
                        () -> MainActivity.setCanValues(
                                1, customFrames, "custom command (unlock/wake)"));
                if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
                    return CycleResult.CANCELLED;
                }
                if (i + 1 < MainActivity.customCommandCount
                        && !waitWhileCurrent(pause, wakeGeneration, restoreEpoch)) {
                    return CycleResult.CANCELLED;
                }
            }
        }
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) {
            return CycleResult.CANCELLED;
        }
        Log.i(TAG, "runCycle: done (source=" + (status == 2 ? "provider" : "cache") + ")");
        return CycleResult.SUCCESS;
    }

    /** Cooperative wait: reset never interrupts the HandlerThread and cancellation latency is bounded. */
    private static boolean waitWhileCurrent(long ms, long wakeGeneration, long restoreEpoch) {
        if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) return false;
        if (ms <= 0) return true;

        // elapsedRealtime, в отличие от uptimeMillis, продолжает идти в deep sleep. Это не даёт
        // паузе 5 с превратиться в «досыпание» после многочасовой стоянки при пропущенном freeze.
        long now = SystemClock.elapsedRealtime();
        long deadline = ms > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ms;
        while (now < deadline) {
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) return false;
            long slice = Math.min(100L, deadline - now);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                // ApplyEngine never interrupts this thread. If somebody else did, abort this cycle
                // and consume the flag so the long-lived HandlerThread is not poisoned permanently.
                Log.w(TAG, "restore wait interrupted; aborting current cycle");
                return false;
            }
            if (!RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch)) return false;
            now = SystemClock.elapsedRealtime();
        }
        return RESTORE_RUN_STATE.isRestoreCurrent(wakeGeneration, restoreEpoch);
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
        FAILED,
        CANCELLED
    }

    /** Android-free cancellation/coverage state, kept package-visible for deterministic unit tests. */
    static final class RestoreRunState {
        // generation is the physical wake/sleep lifetime used by postWakeAction. restoreEpoch is
        // narrower: an explicit user/manual command advances it to supersede auto restore without
        // making unrelated wake actions look as if the car had gone to sleep.
        private long generation;
        private long restoreEpoch;
        private long sequence;
        private long lastCycleSequence = -1L;
        private long lastSuccessfulCycleSequence = -1L;
        private long lastCycleCompletedAt = -1L;
        private long lastSuccessfulCycleCompletedAt = -1L;
        private long markedCanWakeGeneration = -1L;
        private long markedCanRestoreEpoch = -1L;
        private long markedCanCoverageThrough = -1L;
        private boolean active;

        synchronized long currentGeneration() {
            return generation;
        }

        synchronized boolean isCurrent(long candidate) {
            return candidate == generation;
        }

        synchronized long currentRestoreEpoch() {
            return restoreEpoch;
        }

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

        /** Gives each wake trigger an exact order relative to cycle completions (no ms timestamp ties). */
        synchronized long registerTrigger(long wakeCandidate, long restoreCandidate) {
            if (!isRestoreCurrent(wakeCandidate, restoreCandidate)) return -1L;
            return ++sequence;
        }

        /** Sequence boundary to be associated with a CAN pass before that pass begins. */
        synchronized long coverageBoundary(long wakeCandidate, long restoreCandidate) {
            return isRestoreCurrent(wakeCandidate, restoreCandidate) ? sequence : -1L;
        }

        synchronized boolean markCanRestoreComplete(long wakeCandidate, long restoreCandidate,
                                                     long coverageThrough) {
            if (!isRestoreCurrent(wakeCandidate, restoreCandidate)
                    || coverageThrough < 0L || coverageThrough > sequence) {
                return false;
            }
            markedCanWakeGeneration = wakeCandidate;
            markedCanRestoreEpoch = restoreCandidate;
            markedCanCoverageThrough = coverageThrough;
            return true;
        }

        /** Supersedes only auto restore; physical-wake actions remain valid and active. */
        synchronized long cancelRestoreAndAdvance() {
            restoreEpoch++;
            clearCoverage();
            return restoreEpoch;
        }

        synchronized long cancelAndAdvance() {
            generation++;
            restoreEpoch++;
            active = false;
            // Coverage from before sleep must never satisfy a trigger from the next physical wake.
            clearCoverage();
            return generation;
        }

        private void clearCoverage() {
            lastCycleSequence = -1L;
            lastSuccessfulCycleSequence = -1L;
            lastCycleCompletedAt = -1L;
            lastSuccessfulCycleCompletedAt = -1L;
            markedCanWakeGeneration = -1L;
            markedCanRestoreEpoch = -1L;
            markedCanCoverageThrough = -1L;
        }

        synchronized boolean completeCycle(long wakeCandidate, long restoreCandidate,
                                           boolean successful, long endedAt) {
            if (!isRestoreCurrent(wakeCandidate, restoreCandidate)) return false;
            if (successful && (markedCanWakeGeneration != wakeCandidate
                    || markedCanRestoreEpoch != restoreCandidate)) {
                return false;
            }
            long completionSequence = ++sequence;
            lastCycleSequence = successful ? markedCanCoverageThrough : completionSequence;
            lastCycleCompletedAt = endedAt;
            if (successful) {
                lastSuccessfulCycleSequence = markedCanCoverageThrough;
                lastSuccessfulCycleCompletedAt = endedAt;
            }
            markedCanWakeGeneration = -1L;
            markedCanRestoreEpoch = -1L;
            markedCanCoverageThrough = -1L;
            return true;
        }

        synchronized Coverage coverage(long wakeCandidate, long restoreCandidate,
                                       long triggerSequence) {
            if (!isRestoreCurrent(wakeCandidate, restoreCandidate) || triggerSequence < 0
                    || lastCycleSequence < triggerSequence) {
                return Coverage.NONE_RESULT;
            }
            if (lastSuccessfulCycleSequence >= triggerSequence) {
                return new Coverage(Coverage.SUCCESS, lastSuccessfulCycleCompletedAt);
            }
            return new Coverage(Coverage.FAILED, lastCycleCompletedAt);
        }

        static final class Coverage {
            static final int NONE = 0;
            static final int FAILED = 1;
            static final int SUCCESS = 2;
            static final Coverage NONE_RESULT = new Coverage(NONE, -1L);

            final int kind;
            final long completedAt;

            Coverage(int kind, long completedAt) {
                this.kind = kind;
                this.completedAt = completedAt;
            }
        }
    }
}
