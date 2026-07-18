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
 * Сервис прогрева высоковольтной батареи.
 *
 * <p>Читает через CanBusService статус теплового менеджмента ВВБ и уличную температуру,
 * отдаёт снимок виджету на главном экране RestoreMode и — если включён «Автоматический
 * прогрев батареи» и на улице ниже {@link #AUTO_TEMP_THRESHOLD_C}°C — запускает прогрев.</p>
 *
 * <p><b>Числовой температуры самой батареи голова не отдаёт</b> (проверено декомпиляцией
 * CanBusService H97C — парсятся только статусы термоменеджмента и уличная температура из
 * климата). Поэтому «температура в градусах» в виджете — уличная (airTempOutCar), а по
 * батарее показываем состояния: нагрев / pre-heat / автоподогрев / причину отказа.</p>
 *
 * <p>Источники данных (всё через ICanBusService callback, как в {@link LightSensorService}):
 * <ul>
 *   <li>onVehicleStateChanged (код 36) — статусы ВВБ по value-ID (1294..1299, 1080, 1265, 958);</li>
 *   <li>onAirConditionChanged (код 4) — уличная температура (поле airTempOutCar, индекс 35);</li>
 *   <li>queryVehicleState (TX=20) при коннекте — форсирует ре-броадкаст текущих статусов.</li>
 * </ul></p>
 *
 * <p>Активация прогрева — {@link MainActivity#sendBatteryHeatCommand()} (CAN-команду
 * пользователь задаёт отдельно). Здесь только РЕШЕНИЕ, когда её слать.</p>
 */
public class BatteryHeatService extends Service {

    private static final String TAG = "$$$ BatteryHeatService $$$";
    private static final String CHANNEL_ID = "battery_heat_channel";

    // Broadcast'ы обмена с RestoreMode UI (виджет «Прогрев батареи»)
    public static final String ACTION_BATTERY_HEAT_UPDATE   = "ru.big.town.anative.BATTERY_HEAT_UPDATE";
    public static final String ACTION_REQUEST_BATTERY_HEAT  = "ru.big.town.anative.REQUEST_BATTERY_HEAT";
    public static final String ACTION_BATTERY_HEAT_ACTIVATE = "ru.big.town.anative.BATTERY_HEAT_ACTIVATE";

    // Порог автоматического прогрева: ниже этой уличной температуры (°C) включаем прогрев.
    private static final int AUTO_TEMP_THRESHOLD_C = 10;
    // Значение уличной температуры, трактуемое как «нет данных» (так отдаёт CanBusService).
    private static final int TEMP_INVALID = -9999;

    // Периодический опрос: обновляем снимок в UI + прогоняем авто-логику. CAN при этом не шлём
    // без необходимости (только read + queryVehicleState) — холостого трафика в шину не создаём.
    private static final long POLL_MS       = 30_000L;
    private static final long BIND_RETRY_MS = 5_000L;
    // Через это время после коннекта форсируем queryVehicleState (снимок статусов).
    private static final long FORCE_QUERY_MS = 6_000L;
    // Анти-спам активации: не пытаемся включать прогрев чаще, чем раз в эти мс.
    private static final long ACTIVATE_REARM_MS = 5 * 60_000L;

    // ICanBusService
    private static final String CANBUS_DESCRIPTOR    = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_CB_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private static final int    TX_addCallback        = 28;
    private static final int    TX_removeCallback     = 29;
    private static final int    TX_queryVehicleState  = 20;
    private static final int    CB_onAirConditionChanged  = 4;
    private static final int    CB_onVehicleStateChanged  = 36;
    private static final String CANBUS_ACTION  = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";

    // value-ID сигналов ВВБ (VehicleState.value), проверены по декомпиляции H97C
    private static final int ID_TEP_CONTROL_SWITCH = 1294; // 1 вкл, 2 выкл
    private static final int ID_TEP_CONTROL_STATUS = 1295; // 0 неактивен, 1 активен(греется), 2 инициализация, 3 резерв
    private static final int ID_TEP_CONTROL_FAIL   = 1296; // причина отказа (см. failText)
    private static final int ID_AUTO_CTRL          = 1298; // авто-термоконтроль ВВБ: 1 вкл, 2 выкл
    private static final int ID_AUTO_CTRL_INFO     = 1299; // инфо-код авто-режима (0..3)
    private static final int ID_DRIVER_PREHEAT_SET = 1080; // предпусковой прогрев (set)
    private static final int ID_PREHEAT_FAIL_STATE = 1265; // причина отказа прогрева (те же коды, что fail)
    private static final int ID_BMS_STATE          = 958;  // 9 = PREHEAT

    // AirCondition: индекс поля airTempOutCar в parcel (0-based). До него: 11 int, 3 float, далее int'ы.
    private static final int AC_OUTCAR_INDEX = 35;

    // Значение «неизвестно» для статусов, которых ещё не приходило
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private Handler handler;

    // Кэш последних статусов ВВБ
    private volatile int ambientTemp   = TEMP_INVALID;
    private volatile int controlStatus = UNKNOWN;
    private volatile int switchState   = UNKNOWN;
    private volatile int failReason    = UNKNOWN;
    private volatile int autoCtrl      = UNKNOWN;
    private volatile int autoCtrlInfo  = UNKNOWN;
    private volatile int preheatSet    = UNKNOWN;
    private volatile int bmsState      = UNKNOWN;

    private long lastActivateElapsed = Long.MIN_VALUE / 2;

    // CanBusService bind-инфраструктура
    private IBinder canBusBinder = null;
    private boolean canBusBound  = false;
    private boolean canBusCallbackAdded   = false;
    private long    lastCanBusBindAttempt = 0L;
    private boolean forceQueryScheduled   = false;

    // -------------------------------------------------------------------------
    // ICanBusServiceCallback — stub (сервис вызывает onTransact ONEWAY)
    // -------------------------------------------------------------------------

    private final IBinder canBusCallbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CB_onVehicleStateChanged) {
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int id = -1;
                if (data.readInt() != 0) { data.readInt(); id = data.readInt(); }
                int state = data.readInt();
                final int fId = id, fState = state;
                handler.post(() -> onVehicleState(fId, fState));
                return true;
            }
            if (code == CB_onAirConditionChanged) {
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int outCar = TEMP_INVALID;
                if (data.readInt() != 0) {
                    // Порядок полей AirCondition.writeToParcel: 11 int, 3 float, далее int'ы.
                    // airTempOutCar — индекс 35. Читаем ровно до него (остаток парсела не нужен).
                    for (int i = 0; i <= AC_OUTCAR_INDEX; i++) {
                        if (i >= 11 && i <= 13) data.readFloat();      // airLeft/Right/RearTemperature
                        else outCar = data.readInt();                  // последний прочитанный (i==35) = airTempOutCar
                    }
                }
                final int t = outCar;
                handler.post(() -> onAmbientTemp(t));
                return true;
            }
            // Прочие oneway-колбэки CanBus тихо поглощаем — иначе Binder спамит
            // UNKNOWN_TRANSACTION на каждый (тысячи/сек на голове). Спец-коды — в super.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // -------------------------------------------------------------------------
    // CanBusService bind
    // -------------------------------------------------------------------------

    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            canBusBinder = service;
            canBusBound  = true;
            Log.i(TAG, "CanBusService connected, alive=" + service.isBinderAlive());
            addCanBusCallback();
            if (!forceQueryScheduled) {
                forceQueryScheduled = true;
                handler.postDelayed(BatteryHeatService.this::queryVehicleState, FORCE_QUERY_MS);
            }
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
            Log.i(TAG, "addCanBusCallback: OK result=" + result);
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
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "removeCanBusCallback: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
            canBusCallbackAdded = false;
        }
    }

    /** Форсирует ре-броадкаст всех кэшированных VehicleState — снимок статусов на коннекте. */
    private void queryVehicleState() {
        if (!canBusBound || canBusBinder == null) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            canBusBinder.transact(TX_queryVehicleState, data, reply, 0);
            reply.readException();
            Log.i(TAG, "queryVehicleState: OK");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "queryVehicleState: error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // Обработка входящих данных
    // -------------------------------------------------------------------------

    private void onVehicleState(int id, int state) {
        switch (id) {
            case ID_TEP_CONTROL_SWITCH: switchState   = state; break;
            case ID_TEP_CONTROL_STATUS: controlStatus = state; break;
            case ID_TEP_CONTROL_FAIL:   failReason    = state; break;
            case ID_AUTO_CTRL:          autoCtrl      = state; break;
            case ID_AUTO_CTRL_INFO:     autoCtrlInfo  = state; break;
            case ID_DRIVER_PREHEAT_SET: preheatSet    = state; break;
            case ID_PREHEAT_FAIL_STATE:
                // Резервный источник причины отказа, если основной (1296) не приходит.
                if (failReason == UNKNOWN || failReason == 0) failReason = state;
                break;
            case ID_BMS_STATE:          bmsState      = state; break;
            default: return; // не наш сигнал
        }
        Log.i(TAG, "vehicleState id=" + id + " state=" + state);
        broadcastUpdate();
    }

    private void onAmbientTemp(int t) {
        if (t == ambientTemp) return;
        ambientTemp = t;
        Log.i(TAG, "ambientTemp=" + t + "°C");
        broadcastUpdate();
        maybeAutoActivate("temp-change");
    }

    // -------------------------------------------------------------------------
    // Авто-прогрев по уличной температуре < порога
    // -------------------------------------------------------------------------

    /**
     * Если включён «Автоматический прогрев батареи» и на улице ниже порога — запускаем прогрев.
     * Не дёргаем, если прогрев уже активен, или недавно уже запускали (анти-спам), или температура
     * неизвестна. Причина отказа (fail) от BCM отображается в виджете, но саму попытку это не блокирует
     * — решение «можно ли греть» принимает автомобиль.
     */
    private void maybeAutoActivate(String src) {
        if (!isAutoEnabled()) return;
        if (ambientTemp == TEMP_INVALID) return;
        if (ambientTemp >= AUTO_TEMP_THRESHOLD_C) return;
        if (controlStatus == 1) return; // уже греется
        long now = SystemClock.elapsedRealtime();
        if (now - lastActivateElapsed < ACTIVATE_REARM_MS) return;
        lastActivateElapsed = now;
        Log.i(TAG, "AUTO прогрев: " + src + " ambient=" + ambientTemp + "°C < " + AUTO_TEMP_THRESHOLD_C);
        activate("auto <" + AUTO_TEMP_THRESHOLD_C + "°C");
    }

    /** Активация прогрева. Реальную CAN-команду задаёт пользователь в {@link MainActivity#sendBatteryHeatCommand()}. */
    private void activate(String reason) {
        Log.i(TAG, "★ activate battery heat — " + reason);
        MainActivity.sendBatteryHeatCommand();
    }

    // -------------------------------------------------------------------------
    // ContentProvider — настройка «Автоматический прогрев батареи» (колонка 17)
    // -------------------------------------------------------------------------

    private static final Uri CONTENT_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    private static final int COL_BATTERY_HEAT_AUTO = 17;

    private boolean isAutoEnabled() {
        try {
            Cursor c = getContentResolver().query(CONTENT_PROVIDER_URI, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && c.getColumnCount() > COL_BATTERY_HEAT_AUTO)
                        return c.getInt(COL_BATTERY_HEAT_AUTO) == 1;
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isAutoEnabled: " + e.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Broadcast снимка в UI
    // -------------------------------------------------------------------------

    private void broadcastUpdate() {
        Intent i = new Intent(ACTION_BATTERY_HEAT_UPDATE);
        i.putExtra("ambientTemp",   ambientTemp);
        i.putExtra("controlStatus", controlStatus);
        i.putExtra("switchState",   switchState);
        i.putExtra("failReason",    failReason);
        i.putExtra("autoCtrl",      autoCtrl);
        i.putExtra("autoCtrlInfo",  autoCtrlInfo);
        i.putExtra("preheatSet",    preheatSet);
        i.putExtra("bmsState",      bmsState);
        i.putExtra("autoEnabled",   isAutoEnabled() ? 1 : 0);
        i.putExtra("tempThreshold", AUTO_TEMP_THRESHOLD_C);
        sendBroadcast(i);
    }

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_BATTERY_HEAT_ACTIVATE.equals(a)) {
                // Ручная активация из виджета (в один клик).
                lastActivateElapsed = SystemClock.elapsedRealtime();
                activate("manual (виджет)");
            } else {
                // ACTION_REQUEST_BATTERY_HEAT → отдать текущий снимок
                broadcastUpdate();
            }
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() — BatteryHeatService");
        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Прогрев батареи")
                .setContentText("Мониторинг температуры и статуса ВВБ")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(5, notification);

        IntentFilter f = new IntentFilter(ACTION_REQUEST_BATTERY_HEAT);
        f.addAction(ACTION_BATTERY_HEAT_ACTIVATE);
        registerReceiver(uiReceiver, f, RECEIVER_EXPORTED);

        ensureCanBusBound();
        handler.postDelayed(pollRunnable, 2_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        try { unregisterReceiver(uiReceiver); } catch (Exception ignored) {}
        handler.removeCallbacks(pollRunnable);
        if (canBusBound) {
            removeCanBusCallback();
            try { unbindService(canBusConnection); } catch (Exception ignored) {}
            canBusBound = false;
        }
        super.onDestroy();
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            ensureCanBusBound();
            addCanBusCallback();   // no-op если уже добавлен
            queryVehicleState();   // страховка: освежаем снимок статусов
            maybeAutoActivate("poll");
            broadcastUpdate();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Прогрев батареи", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
