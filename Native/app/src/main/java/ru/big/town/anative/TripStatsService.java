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
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Сервис статистики поездок.
 *
 * «Время в пути» = суммарное время, когда КПП в <b>Drive</b> (таймер на паузе при P/R/N).
 * Границы поездки — цикл пробуждения: новая поездка начинается после {@code STATE_ON}
 * (машина проснулась / применились режимы на старте), а не при тумблерах КПП. Быстрые
 * переключения Drive-Parking-Drive-Reverse внутри одного цикла — одна поездка. Время сна
 * в статистику не попадает (таймер идёт только в Drive).
 *
 * Стейт-машина:
 *  - {@code STATE_ON} → финализируем предыдущую поездку (если была) и сбрасываем сессию;
 *  - первый Drive после пробуждения → старт поездки (tripActive), начинаем аккумулировать;
 *  - уход из Drive → добавляем накопленный интервал, пауза;
 *  - финализация на следующем {@code STATE_ON}: если суммарно в Drive < {@link #MIN_TRIP_MS}
 *    (5 мин) — поездку отбрасываем, иначе пишем в лог (последние {@link #MAX_TRIPS}).
 *
 * Сигнал КПП — {@code CanBusService.onGearStatusChanged} (код 12), GearState.value:
 * Parking=0, Reverse=1, Neutral=2, Drive=3, Battery=4, Unknown=-1.
 *
 * Данные отдаём в UI RestoreMode broadcast'ом {@link #ACTION_TRIP_UPDATE} (лог + живое
 * состояние текущей поездки; UI тикает локально по {@code elapsedRealtime}).
 */
public class TripStatsService extends Service {

    private static final String TAG = "$$$ TripStatsService $$$";
    private static final String CHANNEL_ID = "trip_stats_channel";

    /** Форвард STATE_ON из SetModesService — граница новой поездки. */
    public static final String ACTION_POWER_ON = "ru.big.town.anative.TRIP_POWER_ON";

    // Broadcast в RestoreMode UI
    public static final String ACTION_TRIP_UPDATE = "ru.big.town.anative.TRIP_UPDATE";
    public static final String ACTION_REQUEST_TRIP_UPDATE = "ru.big.town.anative.REQUEST_TRIP_UPDATE";
    public static final String ACTION_TRIP_RESET = "ru.big.town.anative.TRIP_RESET"; // ручной сброс таймера в 0
    public static final String ACTION_TRIP_DELETE = "ru.big.town.anative.TRIP_DELETE"; // удалить поездку из истории
    public static final String ACTION_TRIP_HISTORY = "ru.big.town.anative.TRIP_HISTORY"; // вкл/выкл сохранение истории (extra "enabled")
    public static final String EXTRA_DELETE_START = "deleteStart";                     // идентификатор удаляемой (start)
    public static final String EXTRA_TRIP_ACTIVE   = "tripActive";
    public static final String EXTRA_IN_DRIVE      = "inDrive";
    public static final String EXTRA_ACCUM_MS      = "accumMs";
    public static final String EXTRA_DRIVE_START   = "driveStartElapsed"; // elapsedRealtime интервала Drive
    public static final String EXTRA_TRIPS_JSON    = "tripsJson";         // [{start,durationMs}], новые сверху

    // Порог: поездки суммарно короче — не сохраняем
    private static final long MIN_TRIP_MS = 5 * 60 * 1000L;
    private static final int  MAX_TRIPS   = 10;

    // ICanBusService — статус КПП
    private static final String CANBUS_DESCRIPTOR    = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_CB_DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private static final int    TX_addCallback    = 28;
    private static final int    TX_removeCallback = 29;
    private static final int    CB_onGearStatusChanged = 12;
    private static final int    CB_onDoorStatusChanged = 1;   // финализация поездки по открытию двери
    private static final int    CB_onVehicleStateChanged = 36; // диагностика: все VehicleState (в т.ч. RSM уличного датчика света)
    private static final int    CB_onLightStatusChanged  = 10; // диагностика фар: autoLamp/ближний/фары
    // LightStatus: флаг + 17 int'ов; нужные индексы
    private static final int LS_FIELD_COUNT    = 17;
    private static final int LS_IDX_DIPPED_BEAM = 7;
    private static final int LS_IDX_HEAD_LIGHT  = 13;
    private static final int LS_IDX_AUTO_LAMP   = 16;
    private static final String CANBUS_ACTION  = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final int    GEAR_DRIVE = 3;
    // DoorStatus: флаг наличия + 10 int'ов; водительская = fLDoor (индекс 1), OPEN=1
    private static final int DOOR_FIELD_COUNT = 10;
    private static final int DOOR_IDX_FL      = 1;
    private static final int DOOR_OPEN        = 1;

    private static final long SAFETY_POLL_MS = 30_000L;
    private static final long BIND_RETRY_MS  = 5_000L;

    // Persist
    private static final String PREFS = "TripStats";

    private Handler timerHandler;

    private IBinder canBusBinder = null;
    private boolean canBusBound  = false;
    private boolean canBusCallbackAdded   = false;
    private long    lastCanBusBindAttempt = 0L;

    // Состояние текущей поездки
    private boolean tripActive = false;     // был ли первый Drive в этом цикле
    private boolean inDrive    = false;     // сейчас аккумулируем (КПП в Drive)
    private long    accumMs    = 0L;        // накоплено времени в Drive
    private long    driveStartElapsed = 0L; // elapsedRealtime начала текущего интервала Drive
    private long    tripStartWall = 0L;     // wall-clock первого Drive (для даты)
    private int     lastGear = -1;
    private int     lastFLDoor = -1;        // последнее состояние водительской двери

    // -------------------------------------------------------------------------
    // ICanBusServiceCallback — stub (обрабатываем только код 12, gear)
    // -------------------------------------------------------------------------

    private final IBinder canBusCallbackBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CB_onGearStatusChanged) {
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int gearVal = -1;
                if (data.readInt() != 0) {
                    data.readInt();            // ordinal (не нужен)
                    gearVal = data.readInt();  // value
                }
                final int fVal = gearVal;
                timerHandler.post(() -> onGear(fVal));
                return true;
            }
            if (code == CB_onDoorStatusChanged) {
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int fl = -1;
                if (data.readInt() != 0) {
                    for (int i = 0; i < DOOR_FIELD_COUNT; i++) {
                        int v = data.readInt();
                        if (i == DOOR_IDX_FL) fl = v;
                    }
                }
                final int fFl = fl;
                timerHandler.post(() -> onDoor(fFl));
                return true;
            }
            if (code == CB_onLightStatusChanged) {
                // Диагностика фар: LightStatus (флаг + 17 int'ов). autoLamp/ближний/фары.
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int dipped = -1, head = -1, auto = -1;
                if (data.readInt() != 0) {
                    for (int i = 0; i < LS_FIELD_COUNT; i++) {
                        int v = data.readInt();
                        if (i == LS_IDX_DIPPED_BEAM) dipped = v;
                        else if (i == LS_IDX_HEAD_LIGHT) head = v;
                        else if (i == LS_IDX_AUTO_LAMP) auto = v;
                    }
                }
                if (NativeLog.get().isRunning()) {
                    Log.i(TAG, "LIGHTSTATUS autoLamp=" + auto + " dippedBeam=" + dipped + " headLight=" + head);
                }
                return true;
            }
            if (code == CB_onVehicleStateChanged) {
                // Диагностика уличного датчика света (RSM) и прочих VehicleState.
                // Parcel: флаг наличия → VehicleState(ordinal,value=id) → int state.
                data.enforceInterface(CANBUS_CB_DESCRIPTOR);
                int id = -1;
                if (data.readInt() != 0) {
                    data.readInt();          // ordinal (не нужен)
                    id = data.readInt();     // стабильный id сигнала (1070/1071/1072/1073 = RSM)
                }
                int state = data.readInt();  // значение сигнала
                // req 3: синк «последнего активированного» режима при ВНЕШНЕЙ смене (штатное меню машины)
                // → pref RestoreMode. ИНЕРТНО до снятия value-ID на голове (см. maybeSyncMode).
                maybeSyncMode(id, state);
                // Логируем только когда включён захват логов — иначе это «пожарный шланг».
                if (NativeLog.get().isRunning()) {
                    logVehicleState(id, state);
                }
                return true;
            }
            // Прочие oneway-колбэки CanBus (скорость/одометр/…) тихо поглощаем: иначе Binder
            // спамит "UNKNOWN_TRANSACTION" на КАЖДЫЙ (тысячи строк/сек). Спец-коды — в super.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            canBusBinder = service;
            canBusBound  = true;
            Log.i(TAG, "CanBusService connected");
            // Закрываем sync ДО регистрации callback: первый snapshot после (ре)коннекта часто содержит
            // системный wake-дефолт. Одновременно просим восстановить сохранённый snapshot.
            ApplyEngine.scheduleApply("CanBus connected");
            addCanBusCallback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            canBusBinder = null;
            canBusBound  = false;
            canBusCallbackAdded = false;
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
            Log.e(TAG, "ensureCanBusBound: " + e.getMessage(), e);
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
            reply.readInt();
            canBusCallbackAdded = true;
            Log.i(TAG, "addCanBusCallback: OK");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "addCanBusCallback: " + e.getMessage());
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
            Log.w(TAG, "removeCanBusCallback: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
            canBusCallbackAdded = false;
        }
    }

    // -------------------------------------------------------------------------
    // Стейт-машина
    // -------------------------------------------------------------------------

    private void onGear(int gearVal) {
        if (gearVal < 0 || gearVal == lastGear) return;
        lastGear = gearVal;
        boolean nowDrive = (gearVal == GEAR_DRIVE);

        if (nowDrive && !inDrive) {
            if (!tripActive) {
                tripActive = true;
                tripStartWall = System.currentTimeMillis();
                accumMs = 0L;
                Log.i(TAG, "поездка началась (первый Drive в цикле)");
            }
            inDrive = true;
            driveStartElapsed = SystemClock.elapsedRealtime();
            Log.i(TAG, "gear=Drive → таймер идёт");
        } else if (!nowDrive && inDrive) {
            accumMs += SystemClock.elapsedRealtime() - driveStartElapsed;
            inDrive = false;
            Log.i(TAG, "gear=" + gearVal + " → пауза, накоплено=" + fmt(accumMs));
        }
        persistAndBroadcast();
    }

    /** Открытие водительской двери → финализируем поездку («приехал, выхожу»). */
    private void onDoor(int fLDoor) {
        if (fLDoor < 0 || fLDoor == lastFLDoor) return;
        lastFLDoor = fLDoor;
        Log.i(TAG, "door: fLDoor=" + fLDoor);
        if (fLDoor == DOOR_OPEN) {
            Log.i(TAG, "водительская дверь открыта → финализация поездки");
            finalizeTrip("door open");
        }
    }

    /** STATE_ON: страховочная финализация (если открытие двери не поймали). */
    private void onPowerOn() {
        finalizeTrip("power on");
    }

    /** Финализация: закрываем интервал Drive, пишем в лог если ≥ 5 мин, сбрасываем сессию. */
    private void finalizeTrip(String source) {
        if (inDrive) {
            accumMs += SystemClock.elapsedRealtime() - driveStartElapsed;
            inDrive = false;
        }
        if (tripActive) {
            if (accumMs >= MIN_TRIP_MS) {
                addTripToLog(tripStartWall, accumMs);
                Log.i(TAG, "поездка финализирована (" + source + "): " + fmt(accumMs) + " → в лог");
            } else {
                Log.i(TAG, "поездка отброшена (" + source + ", короче 5 мин: " + fmt(accumMs) + ")");
            }
        }
        // сброс сессии
        tripActive = false;
        accumMs = 0L;
        tripStartWall = 0L;
        driveStartElapsed = 0L;
        lastGear = -1;
        persistAndBroadcast();
    }

    /** Ручной сброс таймера в 0 (кнопка «Сброс»). НЕ пишет в лог. Если едем — считаем заново с нуля. */
    private void resetTrip() {
        boolean wasDrive = inDrive;
        accumMs = 0L;
        driveStartElapsed = SystemClock.elapsedRealtime();
        tripStartWall = wasDrive ? System.currentTimeMillis() : 0L;
        tripActive = wasDrive;   // если сейчас едем — поездка продолжается, но с нуля
        inDrive = wasDrive;
        Log.i(TAG, "таймер сброшен вручную (inDrive=" + wasDrive + ")");
        persistAndBroadcast();
    }

    // -------------------------------------------------------------------------
    // Лог поездок (JSON в SharedPreferences)
    // -------------------------------------------------------------------------

    private void addTripToLog(long startWall, long durationMs) {
        if (!prefs().getBoolean("saveHistory", true)) {
            Log.i(TAG, "история поездок выключена — поездка не сохраняется в журнал");
            return;
        }
        try {
            JSONArray arr = new JSONArray(prefs().getString("tripsJson", "[]"));
            JSONObject t = new JSONObject();
            t.put("start", startWall);
            t.put("durationMs", durationMs);
            // Новые сверху; обрезаем до MAX_TRIPS
            JSONArray out = new JSONArray();
            out.put(t);
            for (int i = 0; i < arr.length() && out.length() < MAX_TRIPS; i++) {
                out.put(arr.get(i));
            }
            prefs().edit().putString("tripsJson", out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "addTripToLog: " + e.getMessage());
        }
    }

    /** Вкл/выкл сохранение истории. При выключении — удаляем существующий журнал. */
    private void setHistoryEnabled(boolean enabled) {
        prefs().edit().putBoolean("saveHistory", enabled).apply();
        if (!enabled) {
            prefs().edit().putString("tripsJson", "[]").apply();
            Log.i(TAG, "история поездок ВЫКЛ → журнал очищен");
        } else {
            Log.i(TAG, "история поездок ВКЛ");
        }
        broadcastUpdate();
    }

    private String tripsJson() {
        return prefs().getString("tripsJson", "[]");
    }

    /** Удаляет из лога поездку с указанным start (идентификатор). */
    private void deleteTrip(long start) {
        try {
            JSONArray arr = new JSONArray(tripsJson());
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (t.optLong("start", -1) == start) continue; // пропускаем удаляемую
                out.put(t);
            }
            prefs().edit().putString("tripsJson", out.toString()).apply();
            Log.i(TAG, "поездка удалена start=" + start + " → осталось " + out.length());
        } catch (Exception e) {
            Log.w(TAG, "deleteTrip: " + e.getMessage());
        }
        broadcastUpdate();
    }

    // -------------------------------------------------------------------------
    // Persist текущего состояния + broadcast
    // -------------------------------------------------------------------------

    private void persistAndBroadcast() {
        prefs().edit()
                .putBoolean("curActive", tripActive)
                .putBoolean("curInDrive", inDrive)
                .putLong("curAccumMs", accumMs)
                .putLong("curDriveStart", driveStartElapsed)
                .putLong("curStartWall", tripStartWall)
                .putInt("lastGear", lastGear)
                .apply();
        broadcastUpdate();
    }

    private void restoreState() {
        SharedPreferences p = prefs();
        tripActive = p.getBoolean("curActive", false);
        inDrive    = p.getBoolean("curInDrive", false);
        accumMs    = p.getLong("curAccumMs", 0L);
        driveStartElapsed = p.getLong("curDriveStart", 0L);
        tripStartWall = p.getLong("curStartWall", 0L);
        lastGear   = p.getInt("lastGear", -1);
    }

    private void broadcastUpdate() {
        Intent i = new Intent(ACTION_TRIP_UPDATE);
        i.putExtra(EXTRA_TRIP_ACTIVE, tripActive);
        i.putExtra(EXTRA_IN_DRIVE, inDrive);
        i.putExtra(EXTRA_ACCUM_MS, accumMs);
        i.putExtra(EXTRA_DRIVE_START, driveStartElapsed);
        i.putExtra(EXTRA_TRIPS_JSON, tripsJson());
        sendBroadcast(i);
    }

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_TRIP_RESET.equals(action)) {
                timerHandler.post(TripStatsService.this::resetTrip);
            } else if (ACTION_TRIP_DELETE.equals(action)) {
                final long start = intent.getLongExtra(EXTRA_DELETE_START, -1L);
                timerHandler.post(() -> deleteTrip(start));
            } else if (ACTION_TRIP_HISTORY.equals(action)) {
                final boolean enabled = intent.getBooleanExtra("enabled", true);
                timerHandler.post(() -> setHistoryEnabled(enabled));
            } else {
                broadcastUpdate();
            }
        }
    };

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fmt(long ms) {
        long s = ms / 1000; return (s / 60) + "м " + (s % 60) + "с";
    }

    /** Диагностический лог VehicleState. RSM = лобовой датчик дождя/света (управляет автосветом). */
    private void logVehicleState(int id, int state) {
        String name;
        switch (id) {
            case 1070: name = "RSM_FwBrightness";   break; // фронтальная уличная яркость
            case 1071: name = "RSM_AmbBrightness";  break; // окружающая яркость (авто день/ночь: <346 ночь, <675 сумерки)
            case 1072: name = "RSM_lightSWReason";  break; // причина авто-переключения света
            case 1073: name = "RSM_IRBrightness";   break; // ИК / солнечная нагрузка
            default:   name = "id" + id;            break;
        }
        Log.i(TAG, "VSTATE " + name + " (" + id + ") = " + state);
    }

    // ------------------------------------------------------------------------
    // req 3: СИНХРОНИЗАЦИЯ РЕЖИМА ПРИ ВНЕШНЕЙ СМЕНЕ (штатное меню машины и пр.).
    //
    // Наше приложение раньше не узнавало о смене режима вождения/энергии извне → сохранённый режим
    // (который восстанавливается на пробуждении и показан в UI) устаревал. Здесь ловим VehicleState и,
    // если это сигнал режима, пишем «последний активированный» в источник истины (MainActivity.persistSavedMode
    // → pref RestoreMode). Тогда: пробуждение восстановит реальный последний режим, а кнопка руля циклирует
    // относительно него (без «клика в пустоту»).
    //
    // Value-ID сняты на голове H97C (2026-07). ВАЖНО про режим вождения: берём сигнал ВЫБРАННОГО ПУНКТА
    // меню DRIVING_MODE_SET (id 545), а НЕ DRIVING_MODE_SET_FB (id 787) — последний это параметр «режим
    // управления» (эко/стандарт/спорт), которым «Собственный» тоже прикидывается (у Собственного управление
    // может стоять на Спорт → FB=3=Спорт, неразличимо). DRIVING_MODE_SET различает: Собственный=5, Снег=6.
    // (id785=ASC_MODE_SET_FB подвеска — тоже следует за режимом, но может меняться отдельно, не берём.)
    // Энергорежим — IVI_SOC_MODESET (id 957). Оба приходят в наш code=36 колбэк как (id, state).
    private static final int DRIVE_MODE_VSTATE_ID  = 545;  // DRIVING_MODE_SET (выбранный пункт режима вождения)
    private static final int ENERGY_MODE_VSTATE_ID = 957;  // IVI_SOC_MODESET (энергорежим / power mode)

    private void maybeSyncMode(int id, int state) {
        if (id < 0) return;                                // -1 = «нет id» в parcel; не коллизимся с сентинелом
        try {
            final boolean energy;
            final String mode;
            if (id == DRIVE_MODE_VSTATE_ID) {
                energy = false;
                mode = driveModeFromState(state);
            } else if (id == ENERGY_MODE_VSTATE_ID) {
                energy = true;
                mode = energyModeFromState(state);
            } else return;

            // Неизвестный/переходный state нельзя угадывать и тем более сохранять как пользовательский.
            if (mode == null) {
                if (NativeLog.get().isRunning()) {
                    Log.i(TAG, "maybeSyncMode: unknown state ignored id=" + id + " state=" + state);
                }
                return;
            }
            // Во время restore+settle policy либо игнорирует ожидаемое эхо, либо сама запускает
            // корректирующее применение при ECO/другом несовпадении. Provider здесь остаётся неизменным.
            if (!ApplyEngine.shouldPersistModeFeedback(energy, mode)) return;
            // VState может повторяться часто. После первой записи статик MainActivity уже совпадает — no-op.
            if (MainActivity.isLoadedMode(energy, mode)) return;
            MainActivity.persistSavedMode(getApplicationContext(), energy, mode);
        } catch (Exception e) {
            Log.w(TAG, "maybeSyncMode: " + e.getMessage());
        }
    }

    /** DRIVING_MODE_SET (id545) → тег выбранного режима вождения (снято на голове H97C, подтверждено таймингом).
     *  Собственный=5 и Снег=6 — отличимы (в отличие от FB-параметра управления). Неизвестное → не синкать. */
    private static String driveModeFromState(int state) {
        switch (state) {
            case 1: return "ECO";
            case 2: return "COMFORT";
            case 3: return "SPORT";
            case 4: return "OUTING";       // Загород
            case 5: return "INDIVIDUAL";   // Собственный
            case 6: return "SNOW";         // Снег
            default: return null;
        }
    }

    /** IVI_SOC_MODESET (id957) → тег энергорежима (снято на голове H97C).
     *  Неизвестное/переходное значение не синкаем: угадывание раньше безусловно превращало его в REV.
     *  SMART намеренно не угадываем без подтверждённого state на конкретной комплектации. */
    private static String energyModeFromState(int state) {
        switch (state) {
            case 2: return "EV";     // Электро
            case 3: return "REV";    // Гибрид (fuel)
            case 4: return "SREV";   // Топливо (save)
            default: return null;
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate()");
        timerHandler = new Handler(Looper.getMainLooper());
        restoreState();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Статистика поездок")
                .setContentText("Учёт времени в пути")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(4, notification);

        IntentFilter reqFilter = new IntentFilter(ACTION_REQUEST_TRIP_UPDATE);
        reqFilter.addAction(ACTION_TRIP_RESET);
        reqFilter.addAction(ACTION_TRIP_DELETE);
        reqFilter.addAction(ACTION_TRIP_HISTORY);
        registerReceiver(requestReceiver, reqFilter, RECEIVER_EXPORTED);

        ensureCanBusBound();
        timerHandler.postDelayed(safetyRunnable, 2_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand() action=" + action);
        if (ACTION_POWER_ON.equals(action)) {
            timerHandler.post(this::onPowerOn);
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
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        timerHandler.removeCallbacks(safetyRunnable);
        if (canBusBound) {
            removeCanBusCallback();
            try { unbindService(canBusConnection); } catch (Exception ignored) {}
            canBusBound = false;
        }
        super.onDestroy();
    }

    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            ensureCanBusBound();
            addCanBusCallback();
            timerHandler.postDelayed(this, SAFETY_POLL_MS);
        }
    };

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Статистика поездок", NotificationManager.IMPORTANCE_MIN);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
