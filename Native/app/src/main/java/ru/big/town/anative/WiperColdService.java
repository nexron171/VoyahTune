package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

/**
 * Сервис «Сервисный режим дворников» (для холодного сезона).
 *
 * Задача: при открытии водительской двери перевести дворники в сервисное (поднятое)
 * положение — чтобы при парковке в мороз они не примерзали к стеклу. При включении
 * автомобиля (power on) — вернуть дворники в обычный режим.
 *
 * <p><b>Без завязки на температуру.</b> Изначально условие включало проверку мороза,
 * но температура в момент открытия двери не предсказывает ночную (припарковался в +10,
 * ночью −10). Поэтому триггер — только открытие водительской двери; включать опцию
 * пользователь сам на холодный сезон.
 *
 * <p><b>Особенность CAN-команды:</b> одна и та же команда
 * {@code 65 08 00 00 c1 c0 00 00 40 00} работает как <i>переключатель</i> (toggle):
 * первый раз включает сервисный режим, второй — выключает. Поэтому слать её вслепую
 * нельзя (иначе на power on без активного режима мы его наоборот включим). Держим
 * собственную оценку текущего состояния во флаге {@link #PREF_SERVICE_ACTIVE}
 * (персистится в NativePrefs, чтобы пережить перезапуск процесса) и шлём toggle
 * только когда нужен реальный флип:
 * <ul>
 *   <li>дверь водителя ОТКРЫТА + режим НЕ активен → toggle → активен;</li>
 *   <li>power on + режим активен → toggle → выключен (если не активен — не шлём).</li>
 * </ul>
 *
 * <p><b>Сигнал</b> берём из {@code CanBusService} (тот же сервис, что и автосвет,
 * проверен декомпиляцией — см. память reference-canbus-door-temp):
 * <ul>
 *   <li>единая process-wide подписка {@link CanBusEventHub} фильтрует коды двери и передачи
 *       до ограниченной очереди;</li>
 *   <li>seed двери TX=2 выполняется изолированно в фоновом query-потоке hub — колбэки
 *       delta-only, начальное значение не отдают.</li>
 * </ul>
 * Логика <i>level-triggered</i> с guard'ом по флагу активности: это заодно ловит
 * сценарий, когда голова просыпается именно от открытия двери — на seed мы уже видим
 * дверь открытой и срабатываем.
 *
 * <p>Водительская дверь на Voyah Free (левый руль) — передняя левая, поле
 * {@code DoorStatus.fLDoor} (индекс 1). OPEN=1, CLOSED=0.
 *
 * <p>Ограничение: команда — toggle без обратной связи, поэтому наша оценка состояния
 * может разойтись с реальностью, если пользователь переключит сервисный режим вручную
 * подрулевым рычагом. Также требуется, чтобы штатный режим дворников НЕ стоял в Auto.
 */
public class WiperColdService extends Service {

    private static final String TAG = "$$$ WiperColdService $$$";
    private static final String CHANNEL_ID = "wiper_cold_channel";

    /** Отправить toggle-команду ВЫКЛ при power on (если режим считается активным). */
    public static final String ACTION_POWER_ON_RESET = "ru.big.town.anative.WIPER_COLD_POWER_ON_RESET";

    // Наша оценка текущего состояния сервисного режима (персист — переживает рестарт процесса)
    private static final String PREFS_NAME          = "NativePrefs";
    private static final String PREF_SERVICE_ACTIVE = "wiperServiceActive";
    // Флаги-потребители сигнала двери (пишет MainActivity.applyDoorReactor). Сервис может быть запущен
    // ради любого из них, поэтому каждое действие гейтим своим флагом.
    private static final String PREF_WIPER_ENABLED  = "wiperCold";
    private static final String PREF_MEDIA_PAUSE    = "pauseMediaOnDoor";

    // Пауза музыки при открытии двери водителя. Команду отправляем сразу, fade идёт параллельно, а
    // нулевую громкость держим дольше типичного 1–1.5-секундного буфера wireless CarPlay/AndroidAuto.
    private static final int  FADE_STEPS = 12;      // шагов затухания
    private static final long FADE_TOTAL_MS = 500;  // общая длительность затухания
    private static final long REMOTE_AUDIO_DRAIN_MS = 2_200L;

    // Native не может вызвать оригинальный Qinggan KeyManagerReader напрямую. В full-сборке это
    // действие принимает защищённый runtime-receiver в steeringwheelkeys.js; если инжект отсутствует
    // (включая light), ordered-broadcast completion делает безопасный стандартный fallback.
    private static final String MEDIA_PROXY_ACTION = "ru.big.town.anative.MEDIA_KEY_PROXY";
    private static final String KEYMANAGER_PACKAGE = "com.qinggan.keymanager.service";
    private static final int MEDIA_PROXY_ACK = -1;
    private static final int MEDIA_PROXY_UNHANDLED = 0;

    // Одна команда-переключатель (toggle): включает и выключает сервисный режим
    private static final String WIPER_TOGGLE_FRAME = "65 08 00 00 c1 c0 00 00 40 00";
    private static final int    CAN_CMD_NUM        = 1;

    // GearState.value: Parking=0, Reverse=1, Neutral=2, Drive=3, Battery=4, Unknown=-1.
    // «Готов ехать» = передача из Parking (>=1). Это единственный надёжный сигнал зажигания
    // на этой голове: ACC/engine/vehicleKey/ignition-колбэки CanBus не шлёт вообще (проверено логами).
    private static final int    GEAR_MIN_MOVING  = 1;

    // DoorStatus: флаг наличия, bonnet, затем водительская fLDoor.
    private static final int DOOR_OPEN = 1;
    // Окно после power-on reset, в течение которого не поднимаем дворники заново: гасит
    // гонку «сбросили флаг → seed видит дверь всё ещё открытой → снова включает».
    private static final long POWER_ON_RESET_SUPPRESS_MS = 10_000L;

    private volatile Handler timerHandler;
    private volatile Handler mediaHandler;
    private HandlerThread mediaThread;
    private CanBusEventHub.Subscription canBusSubscription;

    private final DoorPauseRunState mediaPauseState = new DoorPauseRunState();
    private final DoorPauseWorkGate mediaPauseWorkGate = new DoorPauseWorkGate();
    private AudioManager mediaFadeAudioManager = null;
    private volatile boolean destroyed = false;
    private boolean wiperTogglePending = false;
    private Boolean queuedWiperTarget = null;

    // Последнее наблюдаемое состояние водительской двери: -1 неизвестно, 0 закрыта, 1 открыта
    private int  lastFLDoor = -1;
    private long lastPowerOnResetElapsed = 0L; // когда последний раз возвращали дворники по power on

    private void onCanBusEvent(CanBusEvent event) {
        if (destroyed) return;
        switch (event.kind) {
            case CONNECTION:
                CanBusEventHub.get(this).requestDriverDoorSeed();
                break;
            case DOOR:
                if (event.origin == CanBusEvent.Origin.LIVE) {
                    onDoorState(event.first);
                } else {
                    applyDoorSeed(event.first);
                }
                break;
            case GEAR:
                onGearState(event.first);
                break;
            default:
                break;
        }
    }

    private void applyDoorSeed(int frontLeft) {
        if (frontLeft < 0) return;
        lastFLDoor = frontLeft;
        Log.i(TAG, "seed: fLDoor=" + lastFLDoor + " active=" + isServiceActive());
        // A snapshot establishes the level but is not a real open edge: never pause media here.
        if (isWiperEnabled()) evaluate("seed");
    }

    // -------------------------------------------------------------------------
    // Логика
    // -------------------------------------------------------------------------

    private void onDoorState(int fLDoor) {
        if (fLDoor < 0 || fLDoor == lastFLDoor) return;
        // Открытие двери = переход В открытое из ЛЮБОГО другого (закрыта/неизвестно). onDoorState
        // приходит только на РЕАЛЬНЫЕ дельта-события (seed выставляет lastFLDoor напрямую и зовёт
        // evaluate, сюда не заходит), так что паузу шлём на настоящее открытие, а не на пробуждении.
        boolean openedNow = (fLDoor == DOOR_OPEN);
        lastFLDoor = fLDoor;
        boolean mediaOn = isMediaPauseEnabled();
        Log.i(TAG, "door: fLDoor=" + fLDoor + " openedNow=" + openedNow + " mediaPause=" + mediaOn
                + " wiper=" + isWiperEnabled() + " active=" + isServiceActive());
        if (openedNow && mediaOn) requestDoorMediaPause();
        if (isWiperEnabled()) evaluate("door");
    }

    /**
     * Level-triggered решение: если водительская дверь открыта И режим ещё не активен —
     * включаем сервисный режим (toggle). Guard по флагу активности не даёт слать повторно,
     * пока не сбросим на power on.
     */
    private void evaluate(String source) {
        boolean active = isServiceActive();
        Log.i(TAG, "evaluate(" + source + "): fLDoor=" + lastFLDoor + " active=" + active);
        if (active) {                                  // уже включён — ждём power on
            Log.i(TAG, "evaluate: пропуск — режим уже активен (wiperServiceActive=true)");
            return;
        }
        // Только что вернули дворники по power on — не поднимаем их сразу обратно
        long sinceReset = SystemClock.elapsedRealtime() - lastPowerOnResetElapsed;
        if (sinceReset < POWER_ON_RESET_SUPPRESS_MS) {
            Log.i(TAG, "evaluate: пропуск — окно после power-on reset (" + sinceReset + "ms)");
            return;
        }
        if (lastFLDoor != DOOR_OPEN) {                 // дверь водителя не открыта
            Log.i(TAG, "evaluate: пропуск — водительская дверь не открыта (fLDoor=" + lastFLDoor + ")");
            return;
        }
        Log.i(TAG, "★ условие выполнено (" + source + "): дверь водителя открыта"
                + " → включаем сервисный режим дворников");
        requestServiceActive(true, "wiper service ON (driver door open)");
    }

    /**
     * Передача сменилась. Когда уходит из Parking (>=1: R/N/D/B) — машина готова ехать,
     * возвращаем дворники в обычный режим. Это замена ненадёжного STATE_ON головы:
     * голова уже включена, когда открываешь дверь, поэтому нового power on при зажигании
     * не происходит; а вот передача начинает публиковаться именно при готовности машины.
     */
    private void onGearState(int gearVal) {
        Log.i(TAG, "gear=" + gearVal + " active=" + isServiceActive());
        if (gearVal >= GEAR_MIN_MOVING) {
            returnWipersIfActive("gear→" + gearVal + " (готов ехать)");
        }
    }

    /**
     * Power on головы (backup-путь). Если персист говорит, что дворники в сервисном
     * режиме — БЕЗУСЛОВНО возвращаем их (toggle), независимо от температуры.
     */
    private void onPowerOnReset() {
        returnWipersIfActive("power on");
    }

    /**
     * Общий возврат: если по нашей оценке дворники подняты — шлём toggle (опускаем),
     * сбрасываем флаг и включаем окно подавления, чтобы seed/дверь не подняли их обратно.
     */
    private void returnWipersIfActive(String reason) {
        if (isServiceActive()) {
            lastPowerOnResetElapsed = SystemClock.elapsedRealtime();
            Log.i(TAG, reason + " → возвращаем дворники в обычный режим (toggle)");
            requestServiceActive(false, "wiper service OFF (" + reason + ")");
        } else {
            Log.i(TAG, reason + " → сервисный режим не активен, команду не шлём");
        }
    }

    private void requestServiceActive(boolean targetActive, String label) {
        if (wiperTogglePending) {
            queuedWiperTarget = targetActive;
            Log.i(TAG, "wiper toggle coalesced, target=" + targetActive);
            return;
        }
        if (isServiceActive() == targetActive) return;
        wiperTogglePending = true;
        ApplyEngine.postWakeAction(label, () -> {
            byte[] frame = MainActivity.parseHexBinary(WIPER_TOGGLE_FRAME);
            Log.i(TAG, "sendToggle: [" + label + "] frame=" + WIPER_TOGGLE_FRAME
                    + " debugMode=" + CanSender.isDebugMode());
            return CanSender.send(CAN_CMD_NUM, frame, label);
        }, result -> {
            if (!destroyed) {
                timerHandler.post(() -> finishWiperToggle(
                        targetActive, label,
                        result == ApplyEngine.WakeActionResult.SUCCESS));
            }
        });
    }

    private void finishWiperToggle(boolean targetActive, String label, boolean sent) {
        if (destroyed) return;
        wiperTogglePending = false;
        if (sent) {
            setServiceActive(targetActive);
        } else {
            Log.w(TAG, "wiper toggle failed/cancelled: " + label);
        }
        Boolean queued = queuedWiperTarget;
        queuedWiperTarget = null;
        if (queued != null && queued != isServiceActive()) {
            requestServiceActive(queued, "wiper coalesced target " + queued);
        }
    }

    // -------------------------------------------------------------------------
    // Персист флага активности (NativePrefs)
    // -------------------------------------------------------------------------

    private boolean isServiceActive() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SERVICE_ACTIVE, false);
    }

    /** Включён ли «Сервисный режим дворников» — гейт для действий с дворниками (сервис может жить и ради паузы музыки). */
    private boolean isWiperEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_WIPER_ENABLED, false);
    }

    /** Включена ли «Пауза музыки при открытии двери водителя». */
    private boolean isMediaPauseEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_MEDIA_PAUSE, false);
    }

    // -------------------------------------------------------------------------
    // Пауза музыки при открытии двери водителя (с плавным затуханием громкости)
    // -------------------------------------------------------------------------

    /**
     * Отправляет PAUSE_ONLY сразу, параллельно гасит громкость за {@link #FADE_TOTAL_MS} и возвращает
     * её лишь после {@link #REMOTE_AUDIO_DRAIN_MS}. Это скрывает уже буферизованный wireless-звук, но
     * не откладывает саму команду паузы. За один door-open команда отправляется ровно один раз.
     */
    private void requestDoorMediaPause() {
        int workGeneration = mediaPauseWorkGate.tryAcquire();
        if (workGeneration == DoorPauseWorkGate.REJECTED_GENERATION) {
            Log.i(TAG, "pauseActiveMediaWithFade: duplicate suppressed");
            return;
        }
        Handler worker = mediaHandler;
        if (destroyed || worker == null
                || !worker.post(() -> pauseActiveMediaWithFadeOnWorker(workGeneration))) {
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
            if (worker == null || !worker.postDelayed(
                    () -> finishMediaFade(generation, workGeneration, false),
                    REMOTE_AUDIO_DRAIN_MS)) {
                finishMediaFade(generation, workGeneration, false);
            }
            return;
        }

        Handler worker = mediaHandler;
        if (worker == null) {
            finishMediaFade(generation, workGeneration, true);
            return;
        }
        final int startVolF = startVol;
        for (int i = 1; i <= FADE_STEPS; i++) {
            final int target = DoorPauseTimeline.fadeStepVolume(startVolF, i, FADE_STEPS);
            worker.postDelayed(() -> {
                if (!mediaPauseState.isCurrent(generation)) return;
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0); }
                catch (Exception ignored) {}
            }, DoorPauseTimeline.fadeStepDelayMs(i, FADE_STEPS, FADE_TOTAL_MS));
        }

        // Не возвращаем громкость сразу после fade: удалённый CP/AA endpoint может ещё 1–1.5 с
        // выдавать уже буферизованный звук после принятия pause.
        if (!worker.postDelayed(() -> {
            if (!mediaPauseState.isCurrent(generation)) return;
            finishMediaFade(generation, workGeneration, true);
        }, DoorPauseTimeline.restoreDelayMs(FADE_TOTAL_MS, REMOTE_AUDIO_DRAIN_MS))) {
            finishMediaFade(generation, workGeneration, true);
        }
    }

    private void finishMediaFade(int generation, int workGeneration, boolean restore) {
        if (!mediaPauseState.isCurrent(generation)) return;
        int restoreVolume = mediaPauseState.finishAndTakeRestoreVolume(generation);
        if (restore && mediaFadeAudioManager != null && restoreVolume >= 0) {
            try {
                mediaFadeAudioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, restoreVolume, 0);
            } catch (Exception e) {
                Log.w(TAG, "finishMediaFade: restore volume: " + e.getMessage());
            }
            Log.i(TAG, "pauseActiveMediaWithFade: volume restored=" + restoreVolume
                    + " after " + REMOTE_AUDIO_DRAIN_MS + "ms drain window");
        }
        mediaFadeAudioManager = null;
        mediaPauseWorkGate.release(workGeneration);
    }

    /** Выбирает ровно одну семантическую команду; direct/noop уже полностью обработаны роутером. */
    private void dispatchDoorPause(AudioManager am, int workGeneration) {
        MediaControlRouter.Result result = MediaControlRouter.dispatch(
                this, MediaControlPolicy.Command.PAUSE_ONLY);
        Log.i(TAG, "dispatchDoorPause: route=" + result.route + " key=" + result.keyCode
                + " pkg=" + result.packageName + " stateClass=" + result.playbackClass);

        if (MediaControlRouter.ROUTE_DIRECT.equals(result.route)
                || MediaControlRouter.ROUTE_NOOP.equals(result.route)) {
            return;
        }

        boolean musicActive = false;
        try { musicActive = am != null && am.isMusicActive(); }
        catch (Exception e) { Log.w(TAG, "dispatchDoorPause: isMusicActive: " + e.getMessage()); }

        if (MediaControlRouter.ROUTE_KEYMANAGER.equals(result.route)) {
            int keyCode = MediaControlPolicy.pauseKeyWithAudioEvidence(
                    result.keyCode, musicActive);
            if (keyCode != result.keyCode) {
                Log.i(TAG, "dispatchDoorPause: active music stream confirms safe PLAY_PAUSE fallback");
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

    private void sendMediaProxy(int keyCode, boolean nativeQinggan,
                                AudioManager fallbackAudioManager, int workGeneration) {
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && keyCode != KeyEvent.KEYCODE_MEDIA_NEXT
                && keyCode != KeyEvent.KEYCODE_MEDIA_PREVIOUS
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
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent deliveredIntent) {
                    if (destroyed || !mediaPauseWorkGate.isLatest(workGeneration)) return;
                    if (getResultCode() == MEDIA_PROXY_ACK) {
                        mediaPauseWorkGate.acknowledgeProxy(workGeneration);
                        return;
                    }
                    if (!mediaPauseWorkGate.tryClaimFallback(workGeneration)) return;
                    Log.w(TAG, "sendMediaProxy: hook unavailable, standard fallback key=" + keyCode);
                    try {
                        dispatchGlobalMediaKey(fallbackAudioManager, keyCode);
                    } finally {
                        mediaPauseWorkGate.finishFallback(workGeneration);
                    }
                }
            }, callbackHandler, MEDIA_PROXY_UNHANDLED, null, null);
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

    /** Last-resort standard key path for light builds or a missing runtime hook. */
    private void dispatchGlobalMediaKey(AudioManager supplied, int keyCode) {
        try {
            AudioManager am = supplied != null
                    ? supplied : (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
            Log.i(TAG, "dispatchGlobalMediaKey: key=" + keyCode);
        } catch (Exception e) {
            Log.w(TAG, "dispatchGlobalMediaKey: " + e.getMessage());
        }
    }

    private void setServiceActive(boolean active) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SERVICE_ACTIVE, active).apply();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() — WiperColdService, wiperServiceActive=" + isServiceActive());
        timerHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Контроль двери водителя")
                .setContentText("Сервисный режим дворников / пауза музыки")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(3, notification);

        mediaThread = new HandlerThread("WiperDoorMedia");
        mediaThread.start();
        mediaHandler = new Handler(mediaThread.getLooper());

        canBusSubscription = CanBusEventHub.get(this).subscribe(
                CanBusEventRouter.INTEREST_CONNECTION
                        | CanBusEventRouter.INTEREST_DOOR
                        | CanBusEventRouter.INTEREST_GEAR,
                null, timerHandler, this::onCanBusEvent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand() action=" + action);
        if (ACTION_POWER_ON_RESET.equals(action)) {
            timerHandler.post(this::onPowerOnReset);
        }
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
        mediaPauseWorkGate.close();
        CanBusEventHub.Subscription subscription = canBusSubscription;
        canBusSubscription = null;
        if (subscription != null) subscription.close();
        if (timerHandler != null) timerHandler.removeCallbacksAndMessages(null);
        Handler media = mediaHandler;
        HandlerThread thread = mediaThread;
        if (media != null && thread != null) {
            boolean queued = media.postAtFrontOfQueue(() -> {
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
                mediaFadeAudioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC, restoreVolume, 0);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: restore media volume: " + e.getMessage());
            }
        }
        mediaFadeAudioManager = null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Сервисный режим дворников", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
