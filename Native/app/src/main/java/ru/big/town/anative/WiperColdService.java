package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
 *   <li>подписка addCallback (TX=28), колбэк {@code ICanBusServiceCallback}:
 *       {@code onDoorStatusChanged(DoorStatus)} код 1;</li>
 *   <li>синхронный seed на коннекте: {@code getDoorStatus()} TX=2 —
 *       колбэки дельта-only, начальное значение не отдают.</li>
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

    // Пауза музыки при открытии двери водителя — плавное затухание громкости, затем KEYCODE_MEDIA_PAUSE.
    private static final int  FADE_STEPS = 12;      // шагов затухания
    private static final long FADE_TOTAL_MS = 500;  // общая длительность затухания

    // Одна команда-переключатель (toggle): включает и выключает сервисный режим
    private static final String WIPER_TOGGLE_FRAME = "65 08 00 00 c1 c0 00 00 40 00";
    private static final int    CAN_CMD_NUM        = 1;

    // ICanBusService — статус дверей
    private static final String CANBUS_DESCRIPTOR    = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_CB_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private static final int    TX_addCallback    = 28;
    private static final int    TX_removeCallback = 29;
    private static final int    TX_getDoorStatus  = 2;
    private static final int    CB_onDoorStatusChanged = 1;
    private static final int    CB_onGearStatusChanged = 12;
    private static final String CANBUS_ACTION  = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";

    // GearState.value: Parking=0, Reverse=1, Neutral=2, Drive=3, Battery=4, Unknown=-1.
    // «Готов ехать» = передача из Parking (>=1). Это единственный надёжный сигнал зажигания
    // на этой голове: ACC/engine/vehicleKey/ignition-колбэки CanBus не шлёт вообще (проверено логами).
    private static final int    GEAR_UNKNOWN     = -1;
    private static final int    GEAR_MIN_MOVING  = 1;

    // DoorStatus: флаг наличия + 10 int'ов; водительская = fLDoor (индекс 1)
    private static final int DOOR_FIELD_COUNT = 10;
    private static final int DOOR_IDX_FL      = 1;
    private static final int DOOR_OPEN        = 1;
    private static final int DOOR_CLOSED      = 0;

    // Периодичность страховочной проверки коннекта/подписки
    private static final long SAFETY_POLL_MS = 30_000L;
    private static final long BIND_RETRY_MS  = 5_000L;
    // Окно после power-on reset, в течение которого не поднимаем дворники заново: гасит
    // гонку «сбросили флаг → seed видит дверь всё ещё открытой → снова включает».
    private static final long POWER_ON_RESET_SUPPRESS_MS = 10_000L;

    private Handler timerHandler;

    private IBinder canBusBinder = null;
    private boolean canBusBound  = false;
    private boolean canBusCallbackAdded   = false;
    private long    lastCanBusBindAttempt = 0L;

    // Последнее наблюдаемое состояние водительской двери: -1 неизвестно, 0 закрыта, 1 открыта
    private int  lastFLDoor = -1;
    private long lastPowerOnResetElapsed = 0L; // когда последний раз возвращали дворники по power on

    // DEBUG: какие коды колбэков уже видели (лог каждого кода один раз — понять, приходят ли события)
    private final Set<Integer> seenCodes = new HashSet<>();

    // -------------------------------------------------------------------------
    // ICanBusServiceCallback — stub (получает ВСЕ колбэки, обрабатываем только двери)
    // -------------------------------------------------------------------------

    private final IBinder canBusCallbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // DEBUG: логируем каждый код колбэка один раз — видно, приходят ли события вообще.
            // Только при включённом захвате логов: иначе seenCodes копит все коды CanBus впустую.
            if (NativeLog.get().isRunning() && seenCodes.add(code)) Log.i(TAG, "CB code first-seen: " + code);
            switch (code) {
                case CB_onDoorStatusChanged: { // 1
                    data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                    int fl = -1;
                    int[] doors = new int[DOOR_FIELD_COUNT];
                    Arrays.fill(doors, -999);
                    if (data.readInt() != 0) {
                        for (int i = 0; i < DOOR_FIELD_COUNT; i++) {
                            int v = data.readInt();
                            doors[i] = v;
                            if (i == DOOR_IDX_FL) fl = v;
                        }
                    }
                    // Порядок: bonnet(0) fL(1) fR(2) loadSpace(3) rL(4) rR(5) +4 замка(6-9)
                    Log.i(TAG, "onDoorStatusChanged RAW=" + Arrays.toString(doors) + " → fL(idx1)=" + fl);
                    final int fFl = fl;
                    timerHandler.post(() -> onDoorState(fFl));
                    return true;
                }
                case CB_onGearStatusChanged: { // 12 — передача (parcel: presence, ordinal, value)
                    data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                    int gearVal = GEAR_UNKNOWN;
                    if (data.readInt() != 0) {
                        data.readInt();            // ordinal (не используем)
                        gearVal = data.readInt();  // value: Parking=0,Reverse=1,Neutral=2,Drive=3,Battery=4,Unknown=-1
                    }
                    final int fVal = gearVal;
                    timerHandler.post(() -> onGearState(fVal));
                    return true;
                }
                default:
                    // Прочие oneway-колбэки CanBus (скорость/одометр/десятки сигналов) ТИХО поглощаем:
                    // иначе на КАЖДЫЙ Binder отдаёт UNKNOWN_TRANSACTION и фреймворк спамит в logcat
                    // "oneway function results will be dropped …" (~14 строк/сек — забивает кольцевой лог,
                    // вымывает реальную диагностику). Так же сделано в TripStatsService. Спец-коды (dump/
                    // interface и пр. вне диапазона вызовов) — в super.
                    if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    // -------------------------------------------------------------------------
    // ServiceConnection
    // -------------------------------------------------------------------------

    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            canBusBinder = service;
            canBusBound  = true;
            Log.i(TAG, "CanBusService connected, alive=" + service.isBinderAlive());
            addCanBusCallback();
            seedFromSyncReads();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            canBusBinder = null;
            canBusBound  = false;
            canBusCallbackAdded = false;
            Log.w(TAG, "CanBusService disconnected — will rebind on next poll");
        }
    };

    private void ensureCanBusBound() {
        if (canBusBound) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastCanBusBindAttempt < BIND_RETRY_MS) return;
        lastCanBusBindAttempt = now;
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            boolean ok = bindService(intent, canBusConnection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "ensureCanBusBound: bindService returned " + ok);
        } catch (Exception e) {
            Log.e(TAG, "ensureCanBusBound: exception: " + e.getMessage(), e);
        }
    }

    private void addCanBusCallback() {
        if (!canBusBound || canBusBinder == null || canBusCallbackAdded) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallbackBinder);
            canBusBinder.transact(TX_addCallback, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            canBusCallbackAdded = true;
            Log.i(TAG, "addCanBusCallback: OK (TX=" + TX_addCallback + ") result=" + result);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "addCanBusCallback: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void removeCanBusCallback() {
        if (!canBusBound || canBusBinder == null || !canBusCallbackAdded) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallbackBinder);
            canBusBinder.transact(TX_removeCallback, data, reply, 0);
            reply.readException();
            Log.i(TAG, "removeCanBusCallback: OK");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "removeCanBusCallback: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
            canBusCallbackAdded = false;
        }
    }

    /** Синхронно читает статус водительской двери (колбэки дельта-only — старт нужно засеять). */
    private void seedFromSyncReads() {
        int fl = readDriverDoor();
        if (fl >= 0) lastFLDoor = fl;
        Log.i(TAG, "seed: fLDoor=" + lastFLDoor + " active=" + isServiceActive());
        // На seed трогаем только дворники (level-triggered); паузу музыки НЕ шлём — это состояние на
        // момент коннекта/пробуждения, а не событие открытия двери.
        if (isWiperEnabled()) evaluate("seed");
    }

    /** Синхронно читает статус водительской двери (TX=2, DoorStatus.fLDoor). -1 при ошибке. */
    private int readDriverDoor() {
        if (!canBusBound || canBusBinder == null) return -1;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            canBusBinder.transact(TX_getDoorStatus, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) { Log.i(TAG, "readDriverDoor: null DoorStatus"); return -1; }
            int fl = -1;
            int[] doors = new int[DOOR_FIELD_COUNT];
            Arrays.fill(doors, -999);
            for (int i = 0; i < DOOR_FIELD_COUNT; i++) {
                int v = reply.readInt();
                doors[i] = v;
                if (i == DOOR_IDX_FL) fl = v;
            }
            Log.i(TAG, "readDriverDoor RAW=" + Arrays.toString(doors) + " → fL(idx1)=" + fl);
            return fl;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "readDriverDoor: error: " + e.getMessage());
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
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
        if (openedNow && mediaOn) pauseActiveMediaWithFade();
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
        sendToggle("wiper service ON (driver door open)");
        setServiceActive(true);
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
            sendToggle("wiper service OFF (" + reason + ")");
            setServiceActive(false);
        } else {
            Log.i(TAG, reason + " → сервисный режим не активен, команду не шлём");
        }
    }

    private void sendToggle(String label) {
        byte[] frame = MainActivity.parseHexBinary(WIPER_TOGGLE_FRAME);
        Log.i(TAG, "sendToggle: [" + label + "] frame=" + WIPER_TOGGLE_FRAME
                + " debugMode=" + CanSender.isDebugMode());
        CanSender.send(CAN_CMD_NUM, frame, label);
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
     * Плавно приглушает медиа-громкость до нуля за {@link #FADE_TOTAL_MS}, затем ставит активную
     * медиа-сессию на паузу ({@code KEYCODE_MEDIA_PAUSE}) и возвращает исходный уровень громкости
     * (на паузе он не слышен, зато следующее воспроизведение стартует на нормальной громкости).
     *
     * <p>Затухание через {@link AudioManager#setStreamVolume} по {@code STREAM_MUSIC} — best-effort:
     * на голове с car-audio (CarAudioManager, аудио-зоны) смена stream-громкости может не влиять на
     * реальный выход; в этом случае fade будет незаметен, но сама ПАУЗА отработает в любом случае
     * (dispatchMediaKeyEvent пермишенов не требует). Пауза, а не toggle, — открытие двери именно
     * останавливает музыку, а не переключает play/pause.
     */
    private void pauseActiveMediaWithFade() {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) { return; }
        // Уже идёт затухание (дверь дёрнули повторно за <1с) — не запускаем второй ramp, чтобы не
        // захватить промежуточную (низкую) громкость как «исходную» и не оставить звук приглушённым.
        // Просто ещё раз шлём паузу.
        if (mediaFading) { dispatchMediaPause(am); return; }
        int startVol;
        try {
            startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            Log.w(TAG, "pauseActiveMedia: getStreamVolume: " + e.getMessage());
            dispatchMediaPause(am);
            return;
        }
        Log.i(TAG, "pauseActiveMediaWithFade: startVol=" + startVol + " (дверь водителя открыта)");
        if (startVol <= 0) { dispatchMediaPause(am); return; }

        mediaFading = true;
        final int startVolF = startVol;
        final long stepMs = Math.max(30, FADE_TOTAL_MS / FADE_STEPS);
        for (int i = 1; i <= FADE_STEPS; i++) {
            final int target = Math.round(startVolF * (float) (FADE_STEPS - i) / FADE_STEPS);
            timerHandler.postDelayed(() -> {
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, target), 0); } catch (Exception ignored) {}
            }, stepMs * i);
        }
        // После затухания: пауза + вернуть исходную громкость (на паузе не слышна).
        timerHandler.postDelayed(() -> {
            dispatchMediaPause(am);
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, startVolF, 0); } catch (Exception ignored) {}
            mediaFading = false;
            Log.i(TAG, "pauseActiveMediaWithFade: пауза отправлена, громкость возвращена=" + startVolF);
        }, stepMs * FADE_STEPS + 80);
    }

    private volatile boolean mediaFading = false;

    /** Отправляет KEYCODE_MEDIA_PAUSE (down+up) активной медиа-сессии. Пермишен не нужен. */
    private void dispatchMediaPause(AudioManager am) {
        try {
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            Log.i(TAG, "dispatchMediaPause: KEYCODE_MEDIA_PAUSE отправлен");
        } catch (Exception e) {
            Log.w(TAG, "dispatchMediaPause: " + e.getMessage());
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

        ensureCanBusBound();
        timerHandler.postDelayed(safetyRunnable, 2_000L);
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
        timerHandler.removeCallbacks(safetyRunnable);
        if (canBusBound) {
            removeCanBusCallback();
            try {
                unbindService(canBusConnection);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: unbindService failed: " + e.getMessage());
            }
            canBusBound = false;
        }
        super.onDestroy();
    }

    // Страховка: держим коннект/подписку
    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            ensureCanBusBound();
            addCanBusCallback(); // no-op если уже добавлен
            timerHandler.postDelayed(this, SAFETY_POLL_MS);
        }
    };

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Сервисный режим дворников", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
