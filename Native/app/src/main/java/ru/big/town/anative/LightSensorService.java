package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Сервис автоматического управления фарами — датчик освещённости + callback-мониторинг BCM.
 *
 * Логика:
 *  - Таймер N сек: опрашивает ICarSignalService.getLightSensorLevel() (TX=36)
 *  - sensor <= thresholdOn → фары ВКЛ, sensor > thresholdOff → ВЫКЛ, между — не менять (гистерезис)
 *  - Пороги и интервал читаются из ContentProvider RestoreMode (колонки 9, 10, 11)
 *  - Статус BCM получается через push-callback onHeadLightStateChanged() (TX=7 со стороны сервиса)
 *    вместо синхронного поллинга TX=43. Callback регистрируется при коннекте (TX=46),
 *    снимается в onDestroy (TX=47).
 *  - При каждом поллинге: если bcmStatus != expectedHeadLampStatus → повторная CAN (восстановление).
 *  - Fallback: если callback ещё ни разу не пришёл (bcmStatus=-1) → CAN при каждом тике.
 */
public class LightSensorService extends Service {

    private static final String TAG = "$$$ LightSensorService $$$";
    private static final String CHANNEL_ID = "light_sensor_channel";

    // Broadcast для передачи уровня датчика в RestoreMode UI
    public static final String ACTION_LUX_UPDATE  = "ru.big.town.anative.LUX_UPDATE";
    public static final String EXTRA_SENSOR_LEVEL = "sensorLevel"; // int, -1 если датчик недоступен

    // Интервал поллинга по умолчанию (если не задан в prefs)
    private static final long DEFAULT_SENSOR_INTERVAL_MS = 5_000L;

    // ICarSignalService — transact через IBinder напрямую
    private static final String CAR_SIGNAL_DESCRIPTOR = "com.qinggan.carsignal.ICarSignalService";
    private static final int    TX_getLightSensorLevel = 36;
    private static final int    TX_registerCallback    = 46;
    private static final int    TX_unregisterCallback  = 47;

    // ICarSignalServiceCallBack — descriptor нашего stub-а (сервис вызывает методы на нём)
    private static final String CALLBACK_DESCRIPTOR = "com.qinggan.carsignal.ICarSignalServiceCallBack";
    // TX-номера, которые сервис использует при вызове методов нашего callback
    private static final int    CB_TX_onHeadLightStateChanged = 7;
    private static final int    CB_TX_onLightSensorChanged    = 13;

    private static final String CAR_SIGNAL_ACTION  = "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_SIGNAL_PACKAGE = "com.qinggan.carsignal.service";

    private Handler timerHandler;
    private IBinder carSignalBinder = null;
    private boolean carSignalBound  = false;

    // Текущее целевое состояние фар (по датчику)
    private boolean headlightsOn = false;

    // Последний статус BCM, применённый после дебаунса.
    // volatile — читается из main thread, записывается из main thread (postDelayed).
    // -1 = ни одного стабильного значения ещё не было
    private volatile int bcmStatus = -1;

    // Последнее сырое значение из callback — буфер до истечения дебаунса
    private volatile int bcmRaw = -1;

    // Дебаунс на onHeadLightStateChanged: ждём 1 сек тишины перед применением
    private static final long BCM_DEBOUNCE_MS = 1_000L;
    private final Runnable bcmDebounceRunnable = () -> {
        bcmStatus = bcmRaw;
        Log.i(TAG, "debounce: bcmStatus settled=" + bcmStatus);
    };

    // Эталонное значение bcmStatus, снятое через 500мс после первой CAN-команды
    // для текущего targetOn. Перезаписывается только при смене targetOn.
    // -1 = эталон ещё не снят → первый поллинг всегда отправит CAN.
    private int expectedHeadLampStatus = -1;

    // targetOn для которого был снят эталон — чтобы понять когда цель изменилась
    private int expectedForTarget = -1; // -1=неизвестно, 0=OFF, 1=ON

    // Последнее значение датчика для broadcast в UI
    private int lastSensorLevel = -1;

    // -------------------------------------------------------------------------
    // ICarSignalServiceCallBack — Binder stub
    // CarSignalService вызывает onTransact() этого binder-а при изменении состояния фар/датчика
    // -------------------------------------------------------------------------

    private final IBinder callbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.enforceInterface(CALLBACK_DESCRIPTOR);

            if (code == CB_TX_onHeadLightStateChanged) {
                int state = data.readInt();
                bcmRaw = state;
                // Дебаунс: сбрасываем предыдущий таймер и ставим новый на 1 сек
                timerHandler.removeCallbacks(bcmDebounceRunnable);
                timerHandler.postDelayed(bcmDebounceRunnable, BCM_DEBOUNCE_MS);
                Log.i(TAG, "callback: onHeadLightStateChanged(" + state + ") — debounce armed");
                if (reply != null) reply.writeNoException();
                return true;
            }

            if (code == CB_TX_onLightSensorChanged) {
                int level = data.readInt();
                Log.v(TAG, "callback: onLightSensorChanged(" + level + ")");
                if (reply != null) reply.writeNoException();
                return true;
            }

            // Остальные callback-методы игнорируем, но отвечаем корректно
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    // -------------------------------------------------------------------------
    // Регистрация / снятие callback в ICarSignalService
    // -------------------------------------------------------------------------

    /**
     * Регистрирует наш callbackBinder в CarSignalService (TX=46).
     * Вызывается сразу после успешного onServiceConnected.
     */
    private void registerCallback() {
        if (!carSignalBound || carSignalBinder == null) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callbackBinder);
            boolean ok = carSignalBinder.transact(TX_registerCallback, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            Log.i(TAG, "registerCallback: transact OK=" + ok + " result=" + result);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "registerCallback: error: " + e.getMessage(), e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Снимает регистрацию callback (TX=47).
     * Вызывается в onDestroy перед unbindService.
     */
    private void unregisterCallback() {
        if (!carSignalBound || carSignalBinder == null) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callbackBinder);
            boolean ok = carSignalBinder.transact(TX_unregisterCallback, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            Log.i(TAG, "unregisterCallback: transact OK=" + ok + " result=" + result);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "unregisterCallback: error: " + e.getMessage(), e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // ServiceConnection к ICarSignalService
    // -------------------------------------------------------------------------

    private final ServiceConnection carSignalConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            carSignalBinder = service;
            carSignalBound  = true;
            Log.i(TAG, "CarSignalService connected: component=" + name
                    + " binder=" + service.getClass().getName()
                    + " alive=" + service.isBinderAlive());
            registerCallback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            carSignalBinder = null;
            carSignalBound  = false;
            bcmStatus = -1; // сбрасываем — callback больше не работает
            bcmRaw    = -1;
            timerHandler.removeCallbacks(bcmDebounceRunnable);
            long retryMs = getIntervalMs();
            Log.w(TAG, "CarSignalService disconnected — retry in " + retryMs + "ms");
            timerHandler.postDelayed(LightSensorService.this::bindCarSignalService, retryMs);
        }
    };

    private void bindCarSignalService() {
        Log.i(TAG, "bindCarSignalService: action=" + CAR_SIGNAL_ACTION
                + " package=" + CAR_SIGNAL_PACKAGE);
        try {
            Intent intent = new Intent(CAR_SIGNAL_ACTION);
            intent.setPackage(CAR_SIGNAL_PACKAGE);
            boolean ok = bindService(intent, carSignalConnection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindCarSignalService: bindService returned " + ok);
        } catch (Exception e) {
            Log.e(TAG, "bindCarSignalService: exception: " + e.getMessage(), e);
        }
    }

    /**
     * Вызывает ICarSignalService.getLightSensorLevel() через IBinder.transact(TX=36).
     * Возвращает значение датчика или -1 при ошибке / отсутствии соединения.
     */
    private int readSensorLevel() {
        if (!carSignalBound || carSignalBinder == null) {
            Log.v(TAG, "readSensorLevel: skip — not bound");
            return -1;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            boolean ok = carSignalBinder.transact(TX_getLightSensorLevel, data, reply, 0);
            Log.v(TAG, "readSensorLevel: transact(TX=" + TX_getLightSensorLevel + ") → " + ok);
            reply.readException();
            int level = reply.readInt();
            Log.v(TAG, "readSensorLevel: raw value=" + level);
            return level;
        } catch (RemoteException e) {
            Log.w(TAG, "readSensorLevel: RemoteException: " + e.getMessage());
            return -1;
        } catch (RuntimeException e) {
            Log.w(TAG, "readSensorLevel: RuntimeException: " + e.getMessage(), e);
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.i(TAG, "onCreate() — LightSensorService starting (callback mode)");
        Log.i(TAG, "  DEFAULT_SENSOR_INTERVAL_MS=" + DEFAULT_SENSOR_INTERVAL_MS);
        Log.i(TAG, "  TX_registerCallback=" + TX_registerCallback);
        Log.i(TAG, "  CB_TX_onHeadLightStateChanged=" + CB_TX_onHeadLightStateChanged);
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        timerHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Автосвет")
                .setContentText("Управление фарами активно")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(2, notification);

        IntentFilter reqFilter = new IntentFilter("ru.big.town.anative.REQUEST_LUX_UPDATE");
        registerReceiver(requestReceiver, reqFilter, RECEIVER_EXPORTED);
        Log.d(TAG, "onCreate: BroadcastReceiver registered for REQUEST_LUX_UPDATE");

        bindCarSignalService();
        startSensorTimer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand()");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy() — headlightsOn=" + headlightsOn
                + " carSignalBound=" + carSignalBound);
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        stopSensorTimer();
        timerHandler.removeCallbacks(bcmDebounceRunnable);
        if (carSignalBound) {
            unregisterCallback();
            try {
                unbindService(carSignalConnection);
                Log.i(TAG, "onDestroy: CarSignalService unbound");
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: unbindService failed: " + e.getMessage());
            }
            carSignalBound = false;
        }
        Log.i(TAG, "onDestroy: service stopping — headlights left as-is (headlightsOn=" + headlightsOn + ")");
        Log.i(TAG, "onDestroy() done");
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Таймер поллинга датчика (каждые 5 сек)
    // -------------------------------------------------------------------------

    private final Runnable sensorRunnable = new Runnable() {
        @Override
        public void run() {
            pollSensorAndSync();
            long interval = getIntervalMs();
            timerHandler.postDelayed(this, interval);
        }
    };

    private void startSensorTimer() {
        long interval = getIntervalMs();
        Log.i(TAG, "Starting sensor timer (interval=" + interval + "ms)");
        // Первый опрос через 2 сек — дать время биндингу установиться
        timerHandler.postDelayed(sensorRunnable, 2_000L);
    }

    private long getIntervalMs() {
        int sec = readIntFromContentProvider(10, 5); // column 10 = lightSensorIntervalSec
        return sec * 1_000L;
    }

    private void stopSensorTimer() {
        timerHandler.removeCallbacks(sensorRunnable);
    }

    /**
     * Основной цикл: каждые N сек.
     * 1. Читаем датчик → вычисляем targetOn с гистерезисом
     *      sensor <= thresholdOn → включить
     *      sensor >  thresholdOff → выключить
     *      между порогами         → не менять текущее состояние
     * 2. Берём текущий статус BCM из последнего callback (bcmStatus, volatile)
     * 3. Если BCM != ожидаемому → отправляем CAN
     *    При смене targetOn — через 500мс фиксируем новый эталон из bcmStatus.
     */
    private void pollSensorAndSync() {
        // Шаг 1: читаем датчик
        int level = readSensorLevel();
        lastSensorLevel = level;

        boolean targetOn;
        if (level >= 0) {
            int thresholdOn  = getThresholdOn();
            int thresholdOff = getThresholdOff();
            if (level <= thresholdOn) {
                targetOn = true;   // темно — включить
            } else if (level > thresholdOff) {
                targetOn = false;  // светло — выключить
            } else {
                targetOn = headlightsOn; // гистерезис — не менять
            }
            Log.i(TAG, "poll: sensor=" + level
                    + " thresholdOn=" + thresholdOn + " thresholdOff=" + thresholdOff
                    + " headlightsOn=" + headlightsOn
                    + " → target=" + (targetOn ? "ON" : "OFF"));
        } else {
            // Датчик недоступен — цель не меняем
            targetOn = headlightsOn;
            Log.w(TAG, "poll: sensor unavailable — target unchanged=" + targetOn);
        }
        headlightsOn = targetOn;

        // Broadcast в UI
        broadcastUpdate(lastSensorLevel);

        // Шаг 2: берём статус BCM из последнего callback
        int currentStatus = bcmStatus;
        Log.i(TAG, "poll: bcm=" + currentStatus
                + " expected=" + expectedHeadLampStatus
                + " target=" + (targetOn ? "ON" : "OFF"));

        // Шаг 3: решаем нужно ли отправлять CAN
        int targetInt = targetOn ? 1 : 0;
        boolean targetChanged = (targetInt != expectedForTarget);

        if (targetChanged) {
            // Цель изменилась → отправляем CAN и через 500мс фиксируем эталон
            Log.i(TAG, "★ poll: target changed (" + expectedForTarget + "→" + targetInt
                    + ") → sending CAN " + (targetOn ? "ON" : "OFF"));
            expectedForTarget = targetInt;
            expectedHeadLampStatus = -1;
            MainActivity.setHeadlights(targetOn);
            timerHandler.postDelayed(() -> {
                // Берём из callback, не делаем синхронный transact
                int snapped = bcmStatus;
                expectedHeadLampStatus = snapped;
                Log.i(TAG, "★ snapshot: expectedHeadLampStatus=" + snapped
                        + " for target=" + (targetOn ? "ON" : "OFF"));
            }, 500);

        } else if (currentStatus < 0) {
            // Callback ещё ни разу не пришёл (сервис только что подключился
            // или не поддерживает callback) — fallback: CAN при каждом тике
            Log.i(TAG, "★ poll: bcm unknown (no callback yet) → sending CAN every tick "
                    + (targetOn ? "ON" : "OFF"));
            MainActivity.setHeadlights(targetOn);

        } else if (currentStatus != expectedHeadLampStatus) {
            // Цель не менялась, но BCM отличается от эталона → кто-то сбросил фары извне
            Log.i(TAG, "★ poll: BCM drifted (bcm=" + currentStatus
                    + " expected=" + expectedHeadLampStatus
                    + ") → restoring CAN " + (targetOn ? "ON" : "OFF"));
            MainActivity.setHeadlights(targetOn);

        } else {
            Log.v(TAG, "poll: OK (bcm=" + currentStatus
                    + " expected=" + expectedHeadLampStatus + ")");
        }
    }

    // -------------------------------------------------------------------------
    // ContentProvider — чтение настроек из RestoreMode
    // -------------------------------------------------------------------------

    private static final Uri CONTENT_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");

    /**
     * Читает int-значение из указанного столбца ContentProvider RestoreMode.
     * При любой ошибке возвращает defaultValue.
     */
    private int readIntFromContentProvider(int columnIndex, int defaultValue) {
        try {
            Cursor cursor = getContentResolver().query(
                    CONTENT_PROVIDER_URI, null, null, null, null);
            if (cursor == null) {
                Log.w(TAG, "readIntFromContentProvider: cursor is null, col=" + columnIndex);
                return defaultValue;
            }
            try {
                if (cursor.moveToFirst()) {
                    int value = cursor.getInt(columnIndex);
                    Log.v(TAG, "readIntFromContentProvider: col=" + columnIndex + " value=" + value);
                    return value;
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "readIntFromContentProvider: error col=" + columnIndex + ": " + e.getMessage());
        }
        return defaultValue;
    }

    /** Порог включения (нижний): sensor < thresholdOn → фары ВКЛ */
    private int getThresholdOn() {
        int threshold = readIntFromContentProvider(9, 3); // column 9 = lightSensorThreshold
        Log.v(TAG, "getThresholdOn=" + threshold);
        return threshold;
    }

    /** Порог выключения (верхний): sensor > thresholdOff → фары ВЫКЛ */
    private int getThresholdOff() {
        int threshold = readIntFromContentProvider(11, 5); // column 11 = lightSensorThresholdOff
        Log.v(TAG, "getThresholdOff=" + threshold);
        return threshold;
    }

    // -------------------------------------------------------------------------
    // Broadcast в RestoreMode UI
    // -------------------------------------------------------------------------

    private void broadcastUpdate(int sensorLevel) {
        Intent intent = new Intent(ACTION_LUX_UPDATE);
        intent.putExtra(EXTRA_SENSOR_LEVEL, sensorLevel);
        sendBroadcast(intent);
    }

    private void rebroadcastLastValue() {
        broadcastUpdate(lastSensorLevel);
        Log.d(TAG, "rebroadcast: sensor=" + lastSensorLevel);
    }

    // Ресивер на запрос немедленного обновления из RestoreMode
    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "REQUEST_LUX_UPDATE received");
            rebroadcastLastValue();
        }
    };

    // -------------------------------------------------------------------------
    // Notification channel
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Автосвет",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
