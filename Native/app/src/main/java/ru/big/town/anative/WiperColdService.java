package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ru.big.town.common.DoorMediaSettings;

/** Door-triggered parking position; a confirmed Drive is the only automatic release. */
public class WiperColdService extends Service {

    private static final String TAG = "$$$ WiperColdService $$$";
    private static final String CHANNEL_ID = "wiper_cold_channel";

    public static final String ACTION_POWER_ON_RECONCILE =
            "ru.big.town.anative.WIPER_COLD_POWER_ON_RECONCILE";
    public static final String ACTION_POWER_ON_RESET =
            "ru.big.town.anative.WIPER_COLD_POWER_ON_RESET";

    private static final String PREFS_NAME = "NativePrefs";
    private static final String PREF_SERVICE_ACTIVE = "wiperServiceActive";
    private static final String PREF_WAIT_FOR_DOOR_CLOSE = "wiperWaitForDoorClose";
    private static final String PREF_POSITION_UNCERTAIN = "wiperPositionUncertain";
    private static final String PREF_WIPER_ENABLED = "wiperCold";
    private static final String PREF_MEDIA_PAUSE = "pauseMediaOnDoor";
    private static final int MAX_SAFE_RETRIES = 6;
    private static final long WIPER_RETRY_MILLIS = 1000;
    private static final AtomicInteger PENDING_WIPER_TOGGLES = new AtomicInteger();

    // Пауза музыки при открытии двери водителя. Команду отправляем сразу, fade идёт параллельно, а
    // нулевую громкость держим дольше типичного 1–1.5-секундного буфера wireless
    // CarPlay/AndroidAuto.
    private static final int FADE_STEPS = 12; // шагов затухания
    private static final long FADE_TOTAL_MS = 500; // общая длительность затухания
    private static final long FADE_BACKPRESSURE_MS = (FADE_TOTAL_MS + FADE_STEPS - 1L) / FADE_STEPS;
    private static final long REMOTE_AUDIO_DRAIN_MS = 2_200L;

    // Native не может вызвать оригинальный Qinggan KeyManagerReader напрямую. Это
    // действие принимает защищённый runtime-receiver в steeringwheelkeys.js; если инжект
    // отсутствует
    // ordered-broadcast completion делает безопасный стандартный fallback.
    private static final String MEDIA_PROXY_ACTION = "ru.big.town.anative.MEDIA_KEY_PROXY";
    private static final String KEYMANAGER_PACKAGE = "com.qinggan.keymanager.service";
    private static final int MEDIA_PROXY_ACK = -1;
    private static final int MEDIA_PROXY_UNHANDLED = 0;

    // Одна команда-переключатель (toggle): включает и выключает сервисный режим
    private static final String WIPER_TOGGLE_FRAME = "65 08 00 00 c1 c0 00 00 40 00";
    private static final int CAN_CMD_NUM = 1;

    private static final int DOOR_OPEN = 1;

    private volatile Handler timerHandler;
    private volatile Handler mediaHandler;
    private HandlerThread mediaThread;
    private VehicleStateControllers vehicleState;
    private GearStateController.Subscription gearStateSubscription;
    private DriverDoorStateController.Subscription driverDoorSubscription;

    private final DoorPauseRunState mediaPauseState = new DoorPauseRunState();
    private final DoorPauseWorkGate mediaPauseWorkGate = new DoorPauseWorkGate();
    private AudioManager mediaFadeAudioManager = null;
    private volatile boolean destroyed = false;
    private boolean wiperTogglePending;
    private ColdWiperState wiperState;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private int safeRetriesRemaining;
    private final Runnable retryWipers = () -> evaluate("retry", false);

    // Последнее наблюдаемое состояние водительской двери: -1 неизвестно, 0 закрыта, 1 открыта
    private int lastFLDoor = -1;

    private void onDriverDoorState(DriverDoorStateController.State state) {
        if (destroyed) return;
        if (state.isLive()) onDoorState(state.frontLeft);
        else applyDoorSeed(state.frontLeft);
    }

    private void applyDoorSeed(int frontLeft) {
        if (frontLeft < 0) return;
        lastFLDoor = frontLeft;
        wiperState.onDoor(frontLeft);
        persistDoorCloseGuard();
        Log.i(TAG, "seed: fLDoor=" + lastFLDoor + " active=" + isServiceActive());
        // A snapshot establishes the level but never pauses media.
        evaluate("seed");
    }

    // -------------------------------------------------------------------------
    // Логика
    // -------------------------------------------------------------------------

    private void onDoorState(int fLDoor) {
        if (fLDoor < 0 || fLDoor == lastFLDoor) return;
        // Открытие двери = переход В открытое из ЛЮБОГО другого (закрыта/неизвестно). onDoorState
        // приходит только на РЕАЛЬНЫЕ дельта-события (seed выставляет lastFLDoor напрямую и зовёт
        // evaluate, сюда не заходит), так что паузу шлём на настоящее открытие, а не на
        // пробуждении.
        boolean openedNow = (fLDoor == DOOR_OPEN);
        lastFLDoor = fLDoor;
        boolean mediaOn = isMediaPauseEnabled();
        Log.i(
                TAG,
                "door: fLDoor="
                        + fLDoor
                        + " openedNow="
                        + openedNow
                        + " mediaPause="
                        + mediaOn
                        + " wiper="
                        + isWiperEnabled()
                        + " active="
                        + isServiceActive());
        if (openedNow && (mediaOn || isMediaPlayEnabled())) requestDoorMediaAction();
        wiperState.onDoor(fLDoor);
        persistDoorCloseGuard();
        evaluate("door");
    }

    private void evaluate(String source) {
        evaluate(source, true);
    }

    private void evaluate(String source, boolean resetRetries) {
        if (destroyed) return;
        if (resetRetries) safeRetriesRemaining = MAX_SAFE_RETRIES;
        boolean settled = reconcileWiperPosition();
        wiperState.setEnabled(isWiperEnabled());
        Boolean target = wiperState.nextTarget();
        Log.i(TAG, "evaluate(" + source + "): target=" + target + " active=" + isServiceActive()
                + " uncertain=" + wiperState.positionUncertain());
        if (wiperTogglePending) return;
        if (!settled) {
            scheduleSafeRetry();
            return;
        }
        if (target == null) {
            timerHandler.removeCallbacks(retryWipers);
            stopIfIdle();
            return;
        }
        if (!PENDING_WIPER_TOGGLES.compareAndSet(0, 1)) {
            scheduleSafeRetry();
            return;
        }
        requestServiceActive(target, "wiper service " + (target ? "ON" : "OFF") + " (" + source + ")");
    }

    private boolean reconcileWiperPosition() {
        if (hasPendingWiperToggle()) return false;
        Map<String, ?> saved = preferences.getAll();
        if (hasPendingWiperToggle()) return false;
        // The terminal writer commits this pair before releasing the process-wide send owner.
        wiperState.reconcile(
                Boolean.TRUE.equals(saved.get(PREF_SERVICE_ACTIVE)),
                Boolean.TRUE.equals(saved.get(PREF_POSITION_UNCERTAIN)));
        return true;
    }

    private void onGearState(GearStateController.State state) {
        if (destroyed) return;
        wiperState.onGear(state.value, state.isFresh());
        persistDoorCloseGuard();
        evaluate("gear " + state.value + " " + state.source);
    }

    static boolean hasPendingWiperToggle() {
        return PENDING_WIPER_TOGGLES.get() != 0;
    }

    private void requestServiceActive(boolean targetActive, String label) {
        wiperTogglePending = true;
        wiperState.onRequested(targetActive);
        AtomicBoolean receiptWritten = new AtomicBoolean();
        AtomicBoolean frameAttempted = new AtomicBoolean();
        ApplyEngine.postWakeAction(
                label,
                () -> CanSender.runGuardedSend(
                        () -> !destroyed && wiperState.canSend(targetActive),
                        () -> {
                            frameAttempted.set(true);
                            wiperState.onFrameAttempted();
                        },
                        () -> {
                            byte[] frame = MainActivity.parseHexBinary(WIPER_TOGGLE_FRAME);
                            receiptWritten.set(true);
                            persistWiperAttemptReceipt();
                            Log.i(TAG, "sendToggle: [" + label + "] frame=" + WIPER_TOGGLE_FRAME);
                            // CanSender rechecks the target after the synchronous receipt write.
                            return CanSender.send(CAN_CMD_NUM, frame, label);
                        }),
                result -> {
                    boolean sent = result == ApplyEngine.WakeActionResult.SUCCESS;
                    try {
                        // Persist accepted sends before the service callback; its host can die meanwhile.
                        if (sent) {
                            setServiceActive(targetActive);
                        } else if (receiptWritten.get() && !frameAttempted.get()) {
                            clearUnattemptedWiperReceipt();
                        }
                    } finally {
                        PENDING_WIPER_TOGGLES.decrementAndGet();
                    }
                    if (!destroyed) {
                        timerHandler.post(
                                () -> finishWiperToggle(targetActive, label, sent, frameAttempted.get()));
                    }
                });
    }

    private void finishWiperToggle(
            boolean targetActive, String label, boolean sent, boolean frameAttempted) {
        if (destroyed) return;
        wiperTogglePending = false;
        if (sent) {
            wiperState.onSent(targetActive);
            evaluate("completion", false);
            return;
        }
        wiperState.onFailed();
        Log.w(TAG, "wiper toggle failed/cancelled: " + label + " attempted=" + frameAttempted);
        if (!frameAttempted && wiperState.nextTarget() != null) {
            // Only retry a suppressed frame. A failed toggle attempt has no reliable position feedback.
            scheduleSafeRetry();
        }
        stopIfIdle();
    }

    private void scheduleSafeRetry() {
        timerHandler.removeCallbacks(retryWipers);
        if (safeRetriesRemaining <= 0) return;
        safeRetriesRemaining--;
        timerHandler.postDelayed(retryWipers, WIPER_RETRY_MILLIS);
    }

    private void persistDoorCloseGuard() {
        preferences.edit()
                .putBoolean(PREF_WAIT_FOR_DOOR_CLOSE, wiperState.waitForDoorClose())
                .apply();
    }

    private void stopIfIdle() {
        if (!isServiceActive()
                && !hasPendingWiperToggle()
                && !isWiperEnabled()
                && !isMediaPauseEnabled()
                && !isMediaPlayEnabled()) stopSelf();
    }

    // -------------------------------------------------------------------------
    // Персист флага активности (NativePrefs)
    // -------------------------------------------------------------------------

    private boolean isServiceActive() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_SERVICE_ACTIVE, false);
    }

    /**
     * Включён ли «Сервисный режим дворников» — гейт для действий с дворниками (сервис может жить и
     * ради паузы музыки).
     */
    private boolean isWiperEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_WIPER_ENABLED, false);
    }

    /** Включена ли «Пауза музыки при открытии двери водителя». */
    private boolean isMediaPauseEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_MEDIA_PAUSE, false);
    }

    private boolean isMediaPlayEnabled() {
        return ApplyEngine.usesAccRestore()
                && getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(DoorMediaSettings.PLAY_ON_DOOR, false);
    }

    private boolean claimDoorMediaPlay() {
        if (!isMediaPlayEnabled()) return false;
        try {
            Bundle result =
                    getContentResolver()
                            .call(
                                    Uri.parse(
                                            "content://ru.big.town.restoremode.restoremodecontentprovider/"),
                                    "driveHookV2",
                                    DoorMediaSettings.CLAIM_PLAY,
                                    null);
            return result != null && result.getInt("protocol") == 2 && result.getBoolean("claimed");
        } catch (RuntimeException error) {
            Log.w(TAG, "Door media ACC claim unavailable", error);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Пауза музыки при открытии двери водителя (с плавным затуханием громкости)
    // -------------------------------------------------------------------------

    /** Serialize the ACC claim and media command without blocking door or wiper callbacks. */
    private void requestDoorMediaAction() {
        int workGeneration = mediaPauseWorkGate.tryAcquire();
        if (workGeneration == DoorPauseWorkGate.REJECTED_GENERATION) {
            Log.i(TAG, "pauseActiveMediaWithFade: duplicate suppressed");
            return;
        }
        Handler worker = mediaHandler;
        if (destroyed || worker == null || !worker.post(() -> runDoorMediaAction(workGeneration))) {
            mediaPauseWorkGate.release(workGeneration);
        }
    }

    private void runDoorMediaAction(int workGeneration) {
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) return;
        if (claimDoorMediaPlay()) {
            try {
                if (destroyed || !isMediaPlayEnabled()) return;
                MediaControlRouter.Result result =
                        MediaControlRouter.dispatch(this, MediaControlPolicy.Command.PLAY_ONLY);
                Log.i(TAG, "Door Play: route=" + result.route + " package=" + result.packageName);
                if (!MediaControlRouter.ROUTE_DIRECT.equals(result.route)
                        && !MediaControlRouter.ROUTE_NOOP.equals(result.route)) {
                    sendMediaProxy(KeyEvent.KEYCODE_MEDIA_PLAY, false, null, workGeneration);
                }
            } finally {
                mediaPauseWorkGate.release(workGeneration);
            }
        } else if (isMediaPauseEnabled()) {
            pauseActiveMediaWithFadeOnWorker(workGeneration);
        } else {
            mediaPauseWorkGate.release(workGeneration);
        }
    }

    /** Runs all AudioManager, MediaSession and ordered-broadcast work off the main looper. */
    private void pauseActiveMediaWithFadeOnWorker(int workGeneration) {
        try {
            runMediaPauseAndFade(workGeneration);
        } catch (Throwable error) {
            Log.w(TAG, "pauseActiveMediaWithFade: " + error.getMessage());
            cancelMediaFadeAndRestoreVolume();
            mediaPauseWorkGate.release(workGeneration);
        }
    }

    private void runMediaPauseAndFade(int workGeneration) {
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) {
            mediaPauseWorkGate.release(workGeneration);
            return;
        }
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        int startVol;
        try {
            startVol = am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            Log.w(TAG, "pauseActiveMedia: getStreamVolume: " + e.getMessage());
            startVol = -1;
        }
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) {
            mediaPauseWorkGate.release(workGeneration);
            return;
        }

        Log.i(TAG, "pauseActiveMediaWithFade: startVol=" + startVol + " (дверь водителя открыта)");
        final int generation = mediaPauseState.begin(startVol);
        if (generation == DoorPauseRunState.REJECTED_GENERATION) {
            mediaPauseWorkGate.release(workGeneration);
            return;
        }
        mediaFadeAudioManager = am;
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) {
            finishMediaFade(generation, workGeneration, true);
            return;
        }

        // Главное исправление AutoKit: команда уходит в t=0 по тому же keymanager-пути, по которому
        // работает физическая кнопка, а не после fade через глобальный PAUSE=127.
        dispatchDoorPause(am, workGeneration);
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) {
            finishMediaFade(generation, workGeneration, true);
            return;
        }

        if (am == null || startVol <= 0) {
            // Даже без ramp держим debounce до конца remote drain window: быстрый повтор 85 до
            // обновления bridge-state мог бы снова включить уже остановленное воспроизведение.
            Handler worker = mediaHandler;
            if (worker == null
                    || !worker.postDelayed(
                            () -> finishMediaFade(generation, workGeneration, false),
                            REMOTE_AUDIO_DRAIN_MS)) {
                finishMediaFade(generation, workGeneration, false);
            }
            return;
        }

        DoorPauseFadeCursor cursor =
                new DoorPauseFadeCursor(startVol, FADE_STEPS, FADE_TOTAL_MS, REMOTE_AUDIO_DRAIN_MS);
        scheduleMediaFadeTick(generation, workGeneration, am, cursor, SystemClock.uptimeMillis());
    }

    /** Keeps at most one fade tick queued and jumps directly to the level due at absolute time. */
    private void scheduleMediaFadeTick(
            int generation,
            int workGeneration,
            AudioManager am,
            DoorPauseFadeCursor cursor,
            long startedUptime) {
        if (!mediaPauseState.isCurrent(generation)) return;
        if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) {
            finishMediaFade(generation, workGeneration, true);
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - startedUptime;
        DoorPauseFadeCursor.Action action = cursor.actionAt(elapsed);
        if (action.kind == DoorPauseFadeCursor.Kind.RESTORE) {
            finishMediaFade(generation, workGeneration, true);
            return;
        }
        boolean attemptedWrite = false;
        if (action.kind == DoorPauseFadeCursor.Kind.WRITE) {
            attemptedWrite = true;
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, action.volume, 0);
            } catch (Exception e) {
                Log.w(TAG, "media fade volume=" + action.volume + ": " + e.getMessage());
            } finally {
                // A failed AudioService call must advance the cursor too; immediate retries could
                // otherwise turn one vendor failure into a tight Binder loop.
                cursor.markAttempted(action.volume);
            }
            elapsed = SystemClock.uptimeMillis() - startedUptime;
            action = cursor.actionAt(elapsed);
            if (action.kind == DoorPauseFadeCursor.Kind.RESTORE) {
                finishMediaFade(generation, workGeneration, true);
                return;
            }
        }

        // If AudioService itself is slower than the nominal fade cadence, do not immediately issue
        // another overdue Binder call. Yield one quantum, then recompute and jump to the latest
        // step.
        long delay =
                action.kind == DoorPauseFadeCursor.Kind.WRITE
                        ? (attemptedWrite ? FADE_BACKPRESSURE_MS : 0L)
                        : action.delayMs;
        delay = cursor.capDelayToRestore(elapsed, delay);
        Handler worker = mediaHandler;
        long executeAt = startedUptime + elapsed + delay;
        if (worker == null
                || !worker.postAtTime(
                        () ->
                                scheduleMediaFadeTick(
                                        generation, workGeneration, am, cursor, startedUptime),
                        executeAt)) {
            finishMediaFade(generation, workGeneration, true);
        }
    }

    private void finishMediaFade(int generation, int workGeneration, boolean restore) {
        if (!mediaPauseState.isCurrent(generation)) return;
        int restoreVolume = mediaPauseState.finishAndTakeRestoreVolume(generation);
        if (restore && mediaFadeAudioManager != null && restoreVolume >= 0) {
            try {
                mediaFadeAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0);
            } catch (Exception e) {
                Log.w(TAG, "finishMediaFade: restore volume: " + e.getMessage());
            }
            Log.i(
                    TAG,
                    "pauseActiveMediaWithFade: volume restored="
                            + restoreVolume
                            + " after "
                            + REMOTE_AUDIO_DRAIN_MS
                            + "ms drain window");
        }
        mediaFadeAudioManager = null;
        mediaPauseWorkGate.release(workGeneration);
    }

    /** Выбирает ровно одну семантическую команду; direct/noop уже полностью обработаны роутером. */
    private void dispatchDoorPause(AudioManager am, int workGeneration) {
        MediaControlRouter.Result result =
                MediaControlRouter.dispatch(this, MediaControlPolicy.Command.PAUSE_ONLY);
        Log.i(
                TAG,
                "dispatchDoorPause: route="
                        + result.route
                        + " key="
                        + result.keyCode
                        + " pkg="
                        + result.packageName
                        + " stateClass="
                        + result.playbackClass);

        if (MediaControlRouter.ROUTE_DIRECT.equals(result.route)
                || MediaControlRouter.ROUTE_NOOP.equals(result.route)) {
            return;
        }

        boolean musicActive = false;
        try {
            musicActive = am != null && am.isMusicActive();
        } catch (Exception e) {
            Log.w(TAG, "dispatchDoorPause: isMusicActive: " + e.getMessage());
        }

        if (MediaControlRouter.ROUTE_KEYMANAGER.equals(result.route)) {
            int keyCode = MediaControlPolicy.pauseKeyWithAudioEvidence(result.keyCode, musicActive);
            if (keyCode != result.keyCode) {
                Log.i(
                        TAG,
                        "dispatchDoorPause: active music stream confirms safe PLAY_PAUSE fallback");
            }
            sendMediaProxy(keyCode, false, am, workGeneration);
            return;
        }
        if (MediaControlRouter.ROUTE_NATIVE.equals(result.route)) {
            // NATIVE_QG is returned for a confirmed active OEM/Bluetooth target. Recreate QG6 in
            // keymanager; the completion fallback uses standard 85 if the hook is unavailable.
            sendMediaProxy(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, am, workGeneration);
        }
    }

    private void sendMediaProxy(
            int keyCode,
            boolean nativeQinggan,
            AudioManager fallbackAudioManager,
            int workGeneration) {
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && keyCode != KeyEvent.KEYCODE_MEDIA_NEXT
                && keyCode != KeyEvent.KEYCODE_MEDIA_PREVIOUS
                && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY
                && keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE) {
            Log.w(TAG, "sendMediaProxy: rejected key=" + keyCode);
            return;
        }
        Intent intent = new Intent(MEDIA_PROXY_ACTION);
        intent.setPackage(KEYMANAGER_PACKAGE);
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("keyCode", keyCode);
        intent.putExtra("nativeQG", nativeQinggan);
        Handler callbackHandler = mediaHandler;
        if (destroyed || callbackHandler == null) return;
        try {
            sendOrderedBroadcast(
                    intent,
                    null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent deliveredIntent) {
                            if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) return;
                            if (getResultCode() == MEDIA_PROXY_ACK) {
                                mediaPauseWorkGate.acknowledgeProxy(workGeneration);
                                return;
                            }
                            if (!mediaPauseWorkGate.tryClaimFallback(workGeneration)) return;
                            Log.w(
                                    TAG,
                                    "sendMediaProxy: hook unavailable, standard fallback key="
                                            + keyCode);
                            try {
                                dispatchGlobalMediaKey(fallbackAudioManager, keyCode);
                            } finally {
                                mediaPauseWorkGate.finishFallback(workGeneration);
                            }
                        }
                    },
                    callbackHandler,
                    MEDIA_PROXY_UNHANDLED,
                    null,
                    null);
        } catch (Exception e) {
            Log.w(TAG, "sendMediaProxy: " + e.getMessage());
            if (!destroyed && mediaPauseWorkGate.tryClaimFallback(workGeneration)) {
                try {
                    dispatchGlobalMediaKey(fallbackAudioManager, keyCode);
                } finally {
                    mediaPauseWorkGate.finishFallback(workGeneration);
                }
            }
        }
    }

    /** Last-resort standard key path when the runtime hook is unavailable. */
    private void dispatchGlobalMediaKey(AudioManager supplied, int keyCode) {
        try {
            AudioManager am =
                    supplied != null ? supplied : (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
            Log.i(TAG, "dispatchGlobalMediaKey: key=" + keyCode);
        } catch (Exception e) {
            Log.w(TAG, "dispatchGlobalMediaKey: " + e.getMessage());
        }
    }

    private void persistWiperAttemptReceipt() {
        // A restart between JNI and its result must not retry an already transmitted toggle.
        if (!preferences.edit().putBoolean(PREF_POSITION_UNCERTAIN, true).commit()) {
            throw new IllegalStateException("Cannot persist wiper position uncertainty before CAN");
        }
    }

    private void clearUnattemptedWiperReceipt() {
        if (!preferences.edit().putBoolean(PREF_POSITION_UNCERTAIN, false).commit()) {
            wiperState.onFrameAttempted();
            Log.w(TAG, "Cannot clear a cancelled wiper attempt receipt");
        }
    }

    private void setServiceActive(boolean active) {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SERVICE_ACTIVE, active)
                .putBoolean(PREF_POSITION_UNCERTAIN, false)
                .commit()) {
            Log.w(TAG, "Cannot persist accepted wiper service position=" + active);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() — WiperColdService, wiperServiceActive=" + isServiceActive());
        timerHandler = new Handler(Looper.getMainLooper());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll();
        wiperState = new ColdWiperState(
                Boolean.TRUE.equals(saved.get(PREF_SERVICE_ACTIVE)),
                Boolean.TRUE.equals(saved.get(PREF_WAIT_FOR_DOOR_CLOSE)),
                Boolean.TRUE.equals(saved.get(PREF_POSITION_UNCERTAIN)));
        wiperState.setEnabled(isWiperEnabled());
        preferenceListener = (changed, key) -> {
            if (PREF_WIPER_ENABLED.equals(key)
                    || PREF_SERVICE_ACTIVE.equals(key)
                    || PREF_POSITION_UNCERTAIN.equals(key)) {
                // SharedPreferences delivers listeners on main, before this sender's completion.
                evaluate("preference " + key, PREF_WIPER_ENABLED.equals(key) || !wiperTogglePending);
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        createNotificationChannel();
        Notification notification =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Контроль двери водителя")
                        .setContentText("Сервисный режим дворников / управление музыкой")
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .build();
        startForeground(3, notification);

        mediaThread = new HandlerThread("WiperDoorMedia");
        mediaThread.start();
        mediaHandler = new Handler(mediaThread.getLooper());

        vehicleState = VehicleStateControllers.get(this);
        gearStateSubscription = vehicleState.gear().subscribeState(timerHandler, this::onGearState);
        driverDoorSubscription =
                vehicleState.driverDoor().subscribe(timerHandler, this::onDriverDoorState);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand() action=" + action);
        vehicleState.requestGearSnapshot();
        // Wake, sticky recreation and option changes reconcile the saved latch without toggling it off.
        timerHandler.post(() -> evaluate("start " + action));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        destroyed = true;
        if (preferences != null && preferenceListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        mediaPauseWorkGate.close();
        GearStateController.Subscription gearSubscription = gearStateSubscription;
        DriverDoorStateController.Subscription doorSubscription = driverDoorSubscription;
        gearStateSubscription = null;
        driverDoorSubscription = null;
        if (gearSubscription != null) gearSubscription.close();
        if (doorSubscription != null) doorSubscription.close();
        if (timerHandler != null) timerHandler.removeCallbacksAndMessages(null);
        Handler media = mediaHandler;
        HandlerThread thread = mediaThread;
        if (media != null && thread != null) {
            boolean queued =
                    media.postAtFrontOfQueue(
                            () -> {
                                try {
                                    cancelMediaFadeAndRestoreVolume();
                                } finally {
                                    media.removeCallbacksAndMessages(null);
                                    mediaHandler = null;
                                    thread.quitSafely();
                                }
                            });
            if (!queued) {
                mediaPauseState.cancelAndTakeRestoreVolume();
                mediaHandler = null;
                thread.quitSafely();
            }
        } else {
            mediaPauseState.cancelAndTakeRestoreVolume();
        }
        super.onDestroy();
    }

    private void cancelMediaFadeAndRestoreVolume() {
        int restoreVolume = mediaPauseState.cancelAndTakeRestoreVolume();
        if (mediaFadeAudioManager != null && restoreVolume >= 0) {
            try {
                mediaFadeAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: restore media volume: " + e.getMessage());
            }
        }
        mediaFadeAudioManager = null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "Сервисный режим дворников",
                        NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
