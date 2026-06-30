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
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Сервис автоматического управления фарами по датчику освещённости.
 *
 * Архитектура (event-driven + safety-poll), проверена декомпиляцией CarSignalService:
 *  - Подписка: регистрируем колбэк через TX=46 (writeStrongBinder). Сервис ONEWAY-ом
 *    вызывает onLightSensorChanged(level) — код 13, дескриптор
 *    "com.qinggan.carsignal.ICarSignalServiceCallBack". Это мгновенный push при
 *    изменении освещённости (уровень 0–7).
 *  - Начальный снимок: колбэки дельта-only (не отдают текущее значение), поэтому
 *    при коннекте читаем уровень синхронно через TX=36 (getLightSensorLevel).
 *  - Safety-poll каждые SAFETY_POLL_MS: страховка от пропущенного события.
 *    CAN шлёт только при реальной смене цели — холостого трафика не создаёт.
 *
 * Решение по уровню → режим (с гистерезисом):
 *  - level ≤ threshOn  → ближний свет (setHeadlights(true))
 *  - level > threshOff → авторежим    (setHeadlights(false))
 *  - между порогами    → не менять
 *
 * CAN отправляется ТОЛЬКО при изменении целевого режима по датчику (heartbeat убран).
 * Следствие: если пользователь ночью вручную переключил фары в иной режим, он
 * сохранится до фактического изменения освещённости — это намеренно.
 * Ограничение железа: при подрулевом в AUTO VehicleCanBusTool может отменять
 * команду ближнего света.
 */
public class LightSensorService extends Service {

    private static final String TAG = "$$$ LightSensorService $$$";
    private static final String CHANNEL_ID = "light_sensor_channel";

    // Broadcast для передачи уровня датчика в RestoreMode UI
    public static final String ACTION_LUX_UPDATE  = "ru.big.town.anative.LUX_UPDATE";
    public static final String EXTRA_SENSOR_LEVEL = "sensorLevel"; // int, -1 если датчик недоступен

    // Период страховочного опроса: ловит пропущенный колбэк, CAN шлёт только при
    // реальной смене цели — холостого трафика не создаёт.
    private static final long SAFETY_POLL_MS = 30_000L;
    // Минимальный интервал между попытками bind, если сервис ещё не подключён
    private static final long BIND_RETRY_MS  = 5_000L;
    // После инициализации подписок один раз принудительно отправляем таргет —
    // гарантия, что на холодном старте режим выставится, не дожидаясь первого
    // изменения освещённости (колбэки дельта-only).
    private static final long FORCE_INIT_MS = 10_000L;
    // Дебаунс значений датчика (callback): реагируем только когда уровень
    // «устоялся» — гасит дребезг и слишком частые переключения.
    private static final long SENSOR_DEBOUNCE_MS = 3_000L;

    // ICarSignalService — transact через IBinder напрямую
    private static final String CAR_SIGNAL_DESCRIPTOR  = "com.qinggan.carsignal.ICarSignalService";
    private static final int    TX_getLightSensorLevel = 36;
    private static final int    TX_registerCallback    = 46;
    private static final int    TX_unregisterCallback  = 47;

    // ICarSignalServiceCallBack — наш stub, сервис вызывает его методы по этим кодам
    private static final String CALLBACK_DESCRIPTOR    = "com.qinggan.carsignal.ICarSignalServiceCallBack";
    private static final int    CB_onLightSensorChanged = 13;

    private static final String CAR_SIGNAL_ACTION  = "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_SIGNAL_PACKAGE = "com.qinggan.carsignal.service";

    private Handler timerHandler;
    private IBinder carSignalBinder = null;
    private boolean carSignalBound  = false;
    private boolean callbackRegistered = false;
    private long    lastBindAttempt = 0L;

    // Текущая зафиксированная цель: true = ближний свет, false = авторежим
    private boolean headlightsOn = false;
    private boolean everSent     = false;
    private boolean forceInitScheduled = false;
    private int     pendingSensorLevel = -1;

    // Последний уровень датчика для broadcast в UI
    private int lastSensorLevel = -1;

    // -------------------------------------------------------------------------
    // ICarSignalServiceCallBack — Binder stub (сервис вызывает onTransact ONEWAY)
    // -------------------------------------------------------------------------

    private final IBinder callbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CB_onLightSensorChanged) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                final int level = data.readInt();
                // Уходим с binder-потока на main и дебаунсим: реагируем только
                // когда значение «устоялось» SENSOR_DEBOUNCE_MS — гасим дребезг.
                timerHandler.post(() -> {
                    pendingSensorLevel = level;
                    timerHandler.removeCallbacks(sensorDebounceRunnable);
                    timerHandler.postDelayed(sensorDebounceRunnable, SENSOR_DEBOUNCE_MS);
                });
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // -------------------------------------------------------------------------
    // ServiceConnection
    // -------------------------------------------------------------------------

    private final ServiceConnection carSignalConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            carSignalBinder = service;
            carSignalBound  = true;
            Log.i(TAG, "CarSignalService connected, alive=" + service.isBinderAlive());
            registerCallback();
            // Подписки готовы — один раз через FORCE_INIT_MS принудительно
            // отправим таргет (на случай холодного старта). Планируем единожды.
            if (!forceInitScheduled) {
                forceInitScheduled = true;
                timerHandler.postDelayed(forceInitRunnable, FORCE_INIT_MS);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            carSignalBinder = null;
            carSignalBound  = false;
            callbackRegistered = false;
            Log.w(TAG, "CarSignalService disconnected — will rebind on next poll");
        }
    };

    private void ensureBound() {
        if (carSignalBound) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBindAttempt < BIND_RETRY_MS) return;
        lastBindAttempt = now;
        try {
            Intent intent = new Intent(CAR_SIGNAL_ACTION);
            intent.setPackage(CAR_SIGNAL_PACKAGE);
            boolean ok = bindService(intent, carSignalConnection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "ensureBound: bindService returned " + ok);
        } catch (Exception e) {
            Log.e(TAG, "ensureBound: exception: " + e.getMessage(), e);
        }
    }

    /** Регистрирует наш callbackBinder в CarSignalService (TX=46, writeStrongBinder). */
    private void registerCallback() {
        if (!carSignalBound || carSignalBinder == null || callbackRegistered) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callbackBinder);
            carSignalBinder.transact(TX_registerCallback, data, reply, 0);
            reply.readException();
            callbackRegistered = true;
            Log.i(TAG, "registerCallback: OK (TX=" + TX_registerCallback + ")");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "registerCallback: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void unregisterCallback() {
        if (!carSignalBound || carSignalBinder == null || !callbackRegistered) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callbackBinder);
            carSignalBinder.transact(TX_unregisterCallback, data, reply, 0);
            reply.readException();
            Log.i(TAG, "unregisterCallback: OK");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "unregisterCallback: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
            callbackRegistered = false;
        }
    }

    /** Синхронно читает уровень датчика (TX=36). -1 при ошибке. */
    private int readSensorLevel() {
        if (!carSignalBound || carSignalBinder == null) return -1;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            carSignalBinder.transact(TX_getLightSensorLevel, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "readSensorLevel: error: " + e.getMessage());
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
        Log.i(TAG, "onCreate() — LightSensorService (event-driven + safety-poll)");
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

        ensureBound();
        timerHandler.postDelayed(safetyRunnable, 2_000L);
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
        Log.i(TAG, "onDestroy() — headlightsOn=" + headlightsOn + " bound=" + carSignalBound);
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        timerHandler.removeCallbacks(safetyRunnable);
        timerHandler.removeCallbacks(forceInitRunnable);
        timerHandler.removeCallbacks(sensorDebounceRunnable);
        if (carSignalBound) {
            unregisterCallback();
            try {
                unbindService(carSignalConnection);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: unbindService failed: " + e.getMessage());
            }
            carSignalBound = false;
        }
        super.onDestroy();
    }

    // Срабатывает, когда значение датчика «устоялось» (дебаунс) — обрабатываем
    // последний полученный уровень.
    private final Runnable sensorDebounceRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingSensorLevel >= 0) onSensorLevel(pendingSensorLevel, "callback");
        }
    };

    // Один раз через FORCE_INIT_MS после готовности подписок принудительно
    // отправляем таргет по текущему уровню датчика — гарантия установки режима
    // на холодном старте.
    private final Runnable forceInitRunnable = new Runnable() {
        @Override
        public void run() {
            int level = readSensorLevel();
            if (level >= 0) {
                onSensorLevel(level, "force-init", true);
            } else {
                Log.w(TAG, "force-init: датчик недоступен — ждём callback/poll");
            }
        }
    };

    // -------------------------------------------------------------------------
    // Safety-poll: страховка (колбэк остаётся основным триггером)
    // -------------------------------------------------------------------------

    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            ensureBound();
            registerCallback(); // no-op если уже зарегистрирован

            int level = readSensorLevel();
            if (level >= 0) {
                onSensorLevel(level, "poll");
            } else {
                Log.w(TAG, "poll: sensor unavailable (bound=" + carSignalBound + ")");
            }
            timerHandler.postDelayed(this, SAFETY_POLL_MS);
        }
    };

    /**
     * Единая точка обработки уровня (из колбэка, начального снимка или safety-poll).
     * Гистерезис → цель; смена цели или heartbeat → CAN.
     */
    private void onSensorLevel(int level, String source) {
        onSensorLevel(level, source, false);
    }

    private void onSensorLevel(int level, String source, boolean force) {
        lastSensorLevel = level;
        broadcastUpdate(level);

        Settings s = readSettings();

        boolean desired;
        if (level <= s.threshOn) {
            desired = true;           // темно → ближний
        } else if (level > s.threshOff) {
            desired = false;          // светло → авто
        } else {
            desired = headlightsOn;   // мёртвая зона — не меняем
        }

        Log.i(TAG, source + ": sensor=" + level
                + " threshOn=" + s.threshOn + " threshOff=" + s.threshOff
                + " current=" + (headlightsOn ? "ближний" : "авто")
                + " desired=" + (desired ? "ближний" : "авто")
                + (force ? " [force]" : ""));

        // CAN при реальной смене цели, первом запуске или принудительной отправке.
        // Heartbeat намеренно убран: если пользователь ночью вручную сменил режим,
        // он сохраняется до фактического изменения освещённости.
        if (force || !everSent || desired != headlightsOn) {
            commit(desired, source);
        }
    }

    private void commit(boolean targetOn, String reason) {
        Log.i(TAG, "★ commit(" + (targetOn ? "ближний" : "авто") + ") — " + reason);
        headlightsOn = targetOn;
        everSent     = true;
        MainActivity.setHeadlights(targetOn);
    }

    // -------------------------------------------------------------------------
    // ContentProvider — настройки RestoreMode (один запрос за вызов)
    // -------------------------------------------------------------------------

    private static final Uri CONTENT_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");

    private static final int COL_THRESHOLD_ON  = 9;
    private static final int COL_THRESHOLD_OFF = 10;

    private static final int DEF_THRESHOLD_ON  = 3;
    private static final int DEF_THRESHOLD_OFF = 5;

    private static final class Settings {
        int threshOn;
        int threshOff;
    }

    private Settings readSettings() {
        Settings s = new Settings();
        s.threshOn  = DEF_THRESHOLD_ON;
        s.threshOff = DEF_THRESHOLD_OFF;
        try {
            Cursor cursor = getContentResolver().query(
                    CONTENT_PROVIDER_URI, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst() && cursor.getColumnCount() > COL_THRESHOLD_OFF) {
                        s.threshOn  = cursor.getInt(COL_THRESHOLD_ON);
                        s.threshOff = cursor.getInt(COL_THRESHOLD_OFF);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readSettings: " + e.getMessage() + " — defaults");
        }
        if (s.threshOn > s.threshOff) {
            int tmp = s.threshOn; s.threshOn = s.threshOff; s.threshOff = tmp;
            Log.w(TAG, "readSettings: thresholds inverted — swapped");
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // Broadcast в RestoreMode UI
    // -------------------------------------------------------------------------

    private void broadcastUpdate(int sensorLevel) {
        Intent intent = new Intent(ACTION_LUX_UPDATE);
        intent.putExtra(EXTRA_SENSOR_LEVEL, sensorLevel);
        sendBroadcast(intent);
    }

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            broadcastUpdate(lastSensorLevel);
        }
    };

    // -------------------------------------------------------------------------
    // Notification channel
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Автосвет", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
