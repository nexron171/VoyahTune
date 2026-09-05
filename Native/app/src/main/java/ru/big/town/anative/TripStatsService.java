package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

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
    // Входящий Gear callback может дать переходный burst. Стейт-машина принимает каждый переход,
    // но disk apply + глобальный UI broadcast публикуют только последний снимок окна.
    private static final long CAN_STATE_PUBLISH_COALESCE_MS = 250L;

    private static final int    GEAR_DRIVE = 3;
    private static final int DOOR_OPEN        = 1;

    // Persist
    private static final String PREFS = "TripStats";

    private Handler timerHandler;
    private GearStateController.Subscription gearStateSubscription;
    private DriverDoorStateController.Subscription driverDoorSubscription;
    private volatile boolean destroyed = false;

    // Состояние текущей поездки
    private boolean tripActive = false;     // был ли первый Drive в этом цикле
    private boolean inDrive    = false;     // сейчас аккумулируем (КПП в Drive)
    private long    accumMs    = 0L;        // накоплено времени в Drive
    private long    driveStartElapsed = 0L; // elapsedRealtime начала текущего интервала Drive
    private long    tripStartWall = 0L;     // wall-clock первого Drive (для даты)
    private int     lastGear = -1;
    private int     lastFLDoor = -1;        // последнее состояние водительской двери
    private boolean canStatePublishPending;
    private final Runnable canStatePublishRunnable = () -> {
        canStatePublishPending = false;
        persistAndBroadcast();
    };
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
        scheduleCanStatePublish();
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

    private void scheduleCanStatePublish() {
        canStatePublishPending = true;
        timerHandler.removeCallbacks(canStatePublishRunnable);
        timerHandler.postDelayed(canStatePublishRunnable, CAN_STATE_PUBLISH_COALESCE_MS);
    }

    private void persistAndBroadcast() {
        canStatePublishPending = false;
        timerHandler.removeCallbacks(canStatePublishRunnable);
        persistState();
        broadcastUpdate();
    }

    private void persistState() {
        prefs().edit()
                .putBoolean("curActive", tripActive)
                .putBoolean("curInDrive", inDrive)
                .putLong("curAccumMs", accumMs)
                .putLong("curDriveStart", driveStartElapsed)
                .putLong("curStartWall", tripStartWall)
                .putInt("lastGear", lastGear)
                .apply();
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
        ContextCompat.registerReceiver(this, requestReceiver, reqFilter,
                ContextCompat.RECEIVER_EXPORTED);

        VehicleStateControllers vehicleState = VehicleStateControllers.get(this);
        gearStateSubscription = vehicleState.gear().subscribe(timerHandler, this::onGear);
        driverDoorSubscription = vehicleState.driverDoor().subscribe(timerHandler, state -> {
            // Seed/replay is a level for consumers such as Wiper, never a trip-finalization edge.
            if (state.isLive()) onDoor(state.frontLeft);
        });
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
        if (canStatePublishPending) {
            timerHandler.removeCallbacks(canStatePublishRunnable);
            canStatePublishPending = false;
            persistState();
        }
        destroyed = true;
        GearStateController.Subscription gearSubscription = gearStateSubscription;
        DriverDoorStateController.Subscription doorSubscription = driverDoorSubscription;
        gearStateSubscription = null;
        driverDoorSubscription = null;
        if (gearSubscription != null) gearSubscription.close();
        if (doorSubscription != null) doorSubscription.close();
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        timerHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Статистика поездок", NotificationManager.IMPORTANCE_MIN);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
