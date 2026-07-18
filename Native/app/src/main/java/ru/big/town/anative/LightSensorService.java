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
 *  - Подписка на CanBusService.onLightStatusChanged (код 10, addCallback TX=28):
 *    даёт LightStatus с полем autoLamp. Когда BCM сам уходит в «авто» (перевод КПП
 *    в Drive сбрасывает фары в auto) при нашем таргете «ближний» — возвращаем ближний.
 *    Ручное «выкл» (autoLamp=0, headLight=0) под правило не попадает — уважается.
 *    Guard HEADLIGHT_GUARD_MS отсекает «эхо» собственных команд. Так заменяется
 *    старый 10-сек поллинг без спама в CAN.
 *    (CarSignalService.onHeadLightStateChanged код 7 НЕ реагирует на смену режима —
 *    проверено на живой машине, поэтому используем именно CanBus.)
 *  - Дебаунс SENSOR_DEBOUNCE_MS на значения датчика — гасит дребезг.
 *  - force-init через FORCE_INIT_MS после коннекта — гарантия установки режима
 *    на холодном старте (колбэки дельта-only).
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

    // ICarSignalService — датчик света (transact через IBinder напрямую)
    private static final String CAR_SIGNAL_DESCRIPTOR  = "com.qinggan.carsignal.ICarSignalService";
    private static final int    TX_getLightSensorLevel = 36;
    private static final int    TX_registerCallback    = 46;
    private static final int    TX_unregisterCallback  = 47;

    // ICarSignalServiceCallBack — наш stub датчика
    private static final String CALLBACK_DESCRIPTOR     = "com.qinggan.carsignal.ICarSignalServiceCallBack";
    private static final int    CB_onLightSensorChanged = 13;

    private static final String CAR_SIGNAL_ACTION  = "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_SIGNAL_PACKAGE = "com.qinggan.carsignal.service";

    // ICanBusService — статус фар (LightStatus.autoLamp) для отлова внешнего сброса режима
    private static final String CANBUS_DESCRIPTOR    = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_CB_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private static final int    TX_addCallback          = 28;
    private static final int    TX_removeCallback       = 29;
    private static final int    CB_onLightStatusChanged = 10;
    private static final int    CB_onVehicleStateChanged = 36;   // тестовый режим: RSM lightSWReason
    private static final int    CB_onGearStatusChanged   = 12;   // перевод в Drive → анти-Auto
    private static final int    GEAR_DRIVE               = 3;
    // BCM_RSM_lightSWReason (value 1072): 0 Day, 1 Others, 2 Dark, 3 Tunnel, 4 Darkstart
    private static final int    RSM_LIGHT_SW_REASON      = 1072;
    // Задержка после перевода в Drive: даём BCM сбросить фары в Auto, затем выставляем наш таргет.
    private static final long   DRIVE_FALLBACK_MS        = 5_000L;
    private static final String CANBUS_ACTION  = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    // Индексы нужных полей в LightStatus (17 int'ов после флага наличия)
    private static final int LS_IDX_DIPPED_BEAM = 7;
    private static final int LS_IDX_HEAD_LIGHT  = 13;
    private static final int LS_IDX_AUTO_LAMP   = 16;
    private static final int LS_FIELD_COUNT     = 17;

    // Окно после нашей CAN-команды, в течение которого статус фар считаем «эхом»
    // своей же команды и игнорируем (защита от самозацикливания).
    private static final long HEADLIGHT_GUARD_MS = 2_500L;
    // Выдержка после того как поймали «авто», прежде чем вернуть таргет. Если за это
    // время состояние ушло из «авто» — переустановку отменяем (debounce + анти-луп).
    private static final long CANBUS_REASSERT_DELAY_MS = 5_000L;

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
    private long    lastCommitElapsed  = 0L;

    // Последний уровень датчика для broadcast в UI
    private int lastSensorLevel = -1;

    // Тестовый режим уличного сенсора: последняя КПП и последнее решение RSM (для анти-Auto по Drive)
    private int lastGear   = -1;
    private int lastReason = -1;

    // CanBusService — подписка на LightStatus (autoLamp)
    private IBinder canBusBinder = null;
    private boolean canBusBound  = false;
    private boolean canBusCallbackAdded   = false;
    private long    lastCanBusBindAttempt = 0L;
    // Последние значимые поля LightStatus — фильтр шума от поворотников/стопа
    private int lastAutoLamp   = -1;
    private int lastDippedBeam = -1;
    private int lastHeadLight  = -1;

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
            // Прочие oneway-колбэки CarSignal (код 7/25/…) тихо поглощаем — иначе Binder
            // спамит UNKNOWN_TRANSACTION на каждый. Спец-коды (INTERFACE/DUMP) — в super.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // -------------------------------------------------------------------------
    // ICanBusServiceCallback — stub для LightStatus (autoLamp)
    // -------------------------------------------------------------------------

    private final IBinder canBusCallbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CB_onLightStatusChanged) {
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                // readInt() = флаг наличия объекта, затем 17 int-полей LightStatus
                int autoLamp = -1, dippedBeam = -1, headLight = -1;
                if (data.readInt() != 0) {
                    for (int i = 0; i < LS_FIELD_COUNT; i++) {
                        int v = data.readInt();
                        if (i == LS_IDX_DIPPED_BEAM) dippedBeam = v;
                        else if (i == LS_IDX_HEAD_LIGHT) headLight = v;
                        else if (i == LS_IDX_AUTO_LAMP) autoLamp = v;
                    }
                }
                final int fAuto = autoLamp, fDipped = dippedBeam, fHead = headLight;
                timerHandler.post(() -> onLightStatusChanged(fAuto, fDipped, fHead));
                return true;
            }
            if (code == CB_onGearStatusChanged) {
                // Перевод в Drive → BCM сбросит фары в Auto; планируем анти-Auto.
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int gearVal = -1;
                if (data.readInt() != 0) { data.readInt(); gearVal = data.readInt(); }
                final int g = gearVal;
                timerHandler.post(() -> onGear(g));
                return true;
            }
            if (code == CB_onVehicleStateChanged) {
                // Тестовый режим уличного сенсора: ловим BCM_RSM_lightSWReason (1072).
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int id = -1;
                if (data.readInt() != 0) { data.readInt(); id = data.readInt(); }
                int state = data.readInt();
                if (id == RSM_LIGHT_SW_REASON) {
                    final int r = state;
                    timerHandler.post(() -> onLightSwReason(r));
                }
                return true;
            }
            // Прочие oneway-колбэки CanBus тихо поглощаем — иначе Binder спамит
            // UNKNOWN_TRANSACTION на каждый (тысячи/сек). Спец-коды — в super.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
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

    // -------------------------------------------------------------------------
    // CanBusService — bind + addCallback (LightStatus)
    // -------------------------------------------------------------------------

    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            canBusBinder = service;
            canBusBound  = true;
            Log.i(TAG, "CanBusService connected, alive=" + service.isBinderAlive());
            addCanBusCallback();
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

    /** Регистрирует canBusCallbackBinder в CanBusService (TX=28, addCallback). */
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
        ensureCanBusBound();
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
        timerHandler.removeCallbacks(canbusReassertRunnable);
        timerHandler.removeCallbacks(driveFallbackRunnable);
        if (carSignalBound) {
            unregisterCallback();
            try {
                unbindService(carSignalConnection);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: unbindService failed: " + e.getMessage());
            }
            carSignalBound = false;
        }
        if (canBusBound) {
            removeCanBusCallback();
            try {
                unbindService(canBusConnection);
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: canbus unbindService failed: " + e.getMessage());
            }
            canBusBound = false;
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

    // Один раз через FORCE_INIT_MS после готовности подписок принудительно выставляем таргет
    // (уличный если известен, иначе фолбэк на салонный) — гарантия установки на холодном старте.
    private final Runnable forceInitRunnable = new Runnable() {
        @Override
        public void run() {
            applyTarget("force-init");
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
            ensureCanBusBound();
            addCanBusCallback(); // no-op если уже добавлен

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
     * Показание салонного датчика (из колбэка/поллинга) — только для индикации в UI.
     * Решения по фарам принимает уличный датчик (lightSWReason) + анти-Auto по Drive/старту;
     * салонный используется лишь как ФОЛБЭК внутри {@link #applyTarget}, а не непрерывно.
     */
    private void onSensorLevel(int level, String source) {
        lastSensorLevel = level;
        broadcastUpdate(level);
    }

    /**
     * Выставить целевой режим фар: приоритет — уличный датчик (последний lightSWReason);
     * если данных улицы нет — фолбэк на салонный уровень по порогам.
     */
    private void applyTarget(String src) {
        Boolean desired = reasonToDesired(lastReason);
        String s2 = src + " ext reason=" + lastReason;
        if (desired == null) {
            int level = readSensorLevel();
            desired = desiredFromCabin(level, readSettings());
            s2 = src + " cabin level=" + level;
        }
        if (desired == null) {
            Log.i(TAG, src + ": нет данных (reason=" + lastReason + ") — не трогаем");
            return;
        }
        Log.i(TAG, s2 + " → " + (desired ? "ближний" : "авто"));
        commit(desired, s2);
    }

    private void commit(boolean targetOn, String reason) {
        Log.i(TAG, "★ commit(" + (targetOn ? "ближний" : "авто") + ") — " + reason);
        headlightsOn      = targetOn;
        everSent          = true;
        lastCommitElapsed = SystemClock.elapsedRealtime();
        MainActivity.setHeadlights(targetOn);
    }

    /**
     * Уличный датчик (BCM_RSM_lightSWReason, лобовой RSM) — основной источник автосвета.
     * 0 Day → авто; 2 Dark / 3 Tunnel / 4 Darkstart → ближний; 1 Others — не меняем.
     */
    private void onLightSwReason(int reason) {
        lastReason = reason; // запоминаем последнее известное состояние улицы (для анти-Auto по Drive)
        Boolean desired = reasonToDesired(reason);
        Log.i(TAG, "RSM lightSWReason=" + reason + " → "
                + (desired == null ? "без изменений" : (desired ? "ближний" : "авто")));
        if (desired == null) return;
        if (!everSent || desired != headlightsOn) commit(desired, "ext-sensor reason=" + reason);
    }

    /** RSM lightSWReason → цель: 0 Day→авто, 2/3/4 Dark/Tunnel/Darkstart→ближний, иначе null. */
    private Boolean reasonToDesired(int reason) {
        switch (reason) {
            case 0: return Boolean.FALSE;                     // день → авто/выкл
            case 2: case 3: case 4: return Boolean.TRUE;      // темно/тоннель → ближний
            default: return null;                             // 1 Others / неизвестно
        }
    }

    /** Салонный уровень (0–7) → цель по порогам (фолбэк, если нет данных уличного). */
    private Boolean desiredFromCabin(int level, Settings s) {
        if (level < 0) return null;
        if (level <= s.threshOn)  return Boolean.TRUE;   // темно → ближний
        if (level >  s.threshOff) return Boolean.FALSE;  // светло → авто
        return null;                                     // мёртвая зона
    }

    /**
     * Перевод КПП в Drive: BCM сам сбрасывает фары в Auto (матрица). Если освещённость не меняется,
     * lightSWReason не придёт и мы застрянем в Auto — поэтому через {@link #DRIVE_FALLBACK_MS}
     * принудительно выставляем таргет.
     */
    private void onGear(int gearVal) {
        if (gearVal < 0 || gearVal == lastGear) return;
        boolean toDrive = (gearVal == GEAR_DRIVE);
        lastGear = gearVal;
        if (toDrive) {
            Log.i(TAG, "gear=Drive → через " + DRIVE_FALLBACK_MS + "мс выставим таргет (анти-Auto)");
            timerHandler.removeCallbacks(driveFallbackRunnable);
            timerHandler.postDelayed(driveFallbackRunnable, DRIVE_FALLBACK_MS);
        }
    }

    // Анти-Auto после Drive: выставляем таргет по уличному датчику (если знаем), иначе по салонному.
    private final Runnable driveFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            applyTarget("drive+5s (анти-Auto)");
        }
    };

    /**
     * Статус фар из CanBusService (LightStatus). Ловим внешний сброс режима: при
     * переводе КПП в Drive BCM уходит в «авто» (autoLamp=1). Если наш таргет —
     * «ближний» (темно), возвращаем ближний. Ручное «выкл» (autoLamp=0, headLight=0)
     * под правило не попадает — уважается. Guard отсекает эхо своих команд.
     */
    private void onLightStatusChanged(int autoLamp, int dippedBeam, int headLight) {
        // Фильтр шума: реагируем только на изменение значимых полей
        // (поворотники/стоп меняют другие поля и сыпят событиями постоянно).
        if (autoLamp == lastAutoLamp && dippedBeam == lastDippedBeam && headLight == lastHeadLight) {
            return;
        }
        lastAutoLamp = autoLamp; lastDippedBeam = dippedBeam; lastHeadLight = headLight;

        long since = SystemClock.elapsedRealtime() - lastCommitElapsed;
        Log.i(TAG, "lightstatus: autoLamp=" + autoLamp + " dippedBeam=" + dippedBeam
                + " headLight=" + headLight
                + " ourTarget=" + (headlightsOn ? "ближний" : "авто")
                + " sinceCommit=" + since + "ms");

        // Любое значимое изменение статуса отменяет отложенную переустановку —
        // решение принимаем заново по свежему состоянию.
        timerHandler.removeCallbacks(canbusReassertRunnable);

        if (!everSent) return;                 // режим ещё не выставляли — ждём force-init
        if (!headlightsOn) return;             // таргет «авто» (светло) — не вмешиваемся
        if (since < HEADLIGHT_GUARD_MS) {      // эхо нашей же команды
            Log.i(TAG, "lightstatus: игнор — эхо нашей команды (" + since + "ms назад)");
            return;
        }
        if (autoLamp == 1) {
            // BCM ушёл в «авто» при таргете «ближний» → Drive-сброс/переключение.
            // Ждём CANBUS_REASSERT_DELAY_MS: если состояние не устаканится обратно —
            // вернём ближний. Отменяемо любым новым значимым событием (см. выше).
            Log.i(TAG, "lightstatus: поймал АВТО при таргете ближний → выдержка "
                    + CANBUS_REASSERT_DELAY_MS + "ms");
            timerHandler.postDelayed(canbusReassertRunnable, CANBUS_REASSERT_DELAY_MS);
        }
    }

    // Отложенная переустановка ближнего после того как поймали «авто». Перед запуском
    // ещё раз проверяем актуальность (таргет ближний и BCM всё ещё в авто).
    private final Runnable canbusReassertRunnable = new Runnable() {
        @Override
        public void run() {
            if (!everSent || !headlightsOn) return;
            if (lastAutoLamp != 1) {           // за время выдержки ушли из «авто» — отменяем
                Log.i(TAG, "canbus-reset: за выдержку состояние ушло из авто — отмена");
                return;
            }
            Log.i(TAG, "canbus-reset: выдержка прошла, BCM всё ещё в авто → возвращаем ближний");
            commit(true, "canbus-reset");
        }
    };

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
