package ru.big.town.anative;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.util.Arrays;
import ru.big.town.common.EnergyWidgetSettings;
import ru.big.town.common.TripProtocol;

/** Persistent trip authority. D starts/resumes; door or manual Stop ends; movement can start after Stop. */
public class TripStatsService extends Service {
    private static final String TAG = "TripStats";
    private static final String CHANNEL_ID = "trip_stats_channel";
    public static final String ACTION_POWER_ON = "ru.big.town.anative.TRIP_POWER_ON";
    public static final String ACTION_TRIP_UPDATE = TripProtocol.UPDATE;
    public static final String ACTION_REQUEST_TRIP_UPDATE = TripProtocol.REQUEST;
    public static final String ACTION_TRIP_STOP = TripProtocol.STOP;
    public static final String ACTION_TRIP_DELETE = TripProtocol.DELETE;
    public static final String ACTION_TRIP_HISTORY = "ru.big.town.anative.TRIP_HISTORY";
    public static final String EXTRA_DELETE_START = TripProtocol.DELETE_START;
    public static final String EXTRA_TRIP_ACTIVE = "tripActive", EXTRA_IN_DRIVE = "inDrive";
    public static final String EXTRA_ACCUM_MS = "accumMs", EXTRA_DRIVE_START = "driveStartElapsed";
    public static final String EXTRA_TRIPS_JSON = "tripsJson";
    private static final String REASON_MANUAL_STOP = "manual stop";
    private static final long CAN_STATE_PUBLISH_COALESCE_MS = 250L;
    private static final long CHECKPOINT_MS = 5_000L;
    private static volatile double[] liveEnergy = {Double.NaN, 0, 0, 0, 0};
    static double[] currentEnergy() { return liveEnergy.clone(); }

    private HandlerThread thread;
    private Handler timerHandler;
    private TripStore store;
    private TripSession session = new TripSession();
    private TripRecorder recorder = new TripRecorder();
    private CanBusEventHub hub;
    private CanBusEventHub.Subscription telemetrySubscription;
    private DriverDoorStateController.Subscription driverDoorSubscription;
    private final float[] sensors = new float[EnergyTelemetrySample.COUNT];
    private final long[] received = new long[EnergyTelemetrySample.COUNT];
    private int lastGear = -1, lastFLDoor = -1;
    private boolean connected, destroyed, canStatePublishPending, gearEventLive;
    private boolean trackingConsent;
    private int odometerSource;
    private long lastSave;
    private String historyJson = "[]";
    private JSONObject pendingFinish;
    private boolean pendingFinishKeep;
    private String pendingFinishReason;
    private final Runnable canStatePublishRunnable = () -> { canStatePublishPending = false; persistAndBroadcast(); };
    private final Runnable checkpointRunnable = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            collect();
            long now = SystemClock.elapsedRealtime();
            if (now - lastSave >= CHECKPOINT_MS) {
                if (pendingFinish != null) saveFinishedTrip();
                else if (session.active || session.waitingForMovement) persistState();
                hub.requestEnergySnapshot(); syncLocationService(); lastSave = now;
                if (lastGear < 0) hub.requestGearSnapshot();
            }
            timerHandler.postDelayed(checkpointRunnable, 1000);
        }
    };

    private void onGear(int gearVal) {
        if (gearVal < 0 || destroyed) return;
        if (pendingFinish != null && !saveFinishedTrip()) return;
        if (gearVal == lastGear) return;
        int previousGear = lastGear;
        lastGear = gearVal;
        if (session.waitingForMovement && !session.allowsDriveStart(gearEventLive, previousGear)) return;
        if (session.waitingForMovement && gearVal != 3) return;
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        if (gearVal == 3 && session.expiresOnDrive(wall)) {
            if (!finalizeTrip("pause timeout", session.pausedWall)) { lastGear = -1; return; }
        }
        boolean wasDrive = session.inDrive;
        if (gearVal != 3) recorder.resumeOdo = Double.NaN;
        session.gear(gearVal == 3, wall, elapsed);
        if (!wasDrive && session.inDrive)
            recorder.levelsAtDrive(sensor(EnergyTelemetrySample.SOC, elapsed), sensor(EnergyTelemetrySample.FUEL, elapsed));
        if (wasDrive != session.inDrive) recorder.breakObservation();
        collect(); publishEnergy(); syncLocationService();
        scheduleCanStatePublish();
    }

    private void onDoor(DriverDoorStateController.State state) {
        if (state.frontLeft < 0) return;
        int previous = lastFLDoor; lastFLDoor = state.frontLeft;
        // A startup snapshot is a level, not evidence of a door opening during downtime.
        if (TripSession.doorOpening(state.isLive(), previous, state.frontLeft)) {
            boolean waiting = session.waitingForMovement;
            session.cancelMovementRestart();
            collect(); finalizeTrip("driver door", System.currentTimeMillis());
            if (waiting && !session.active) persistAndBroadcast();
        }
    }

    private boolean finalizeTrip(String reason, long endWall) {
        if (pendingFinish != null) return saveFinishedTrip();
        if (!session.active) return true;
        long elapsed = SystemClock.elapsedRealtime();
        try {
            if (!"pause timeout".equals(reason))
                recorder.endLevels(sensor(EnergyTelemetrySample.SOC, elapsed), sensor(EnergyTelemetrySample.FUEL, elapsed));
            // Stop timing immediately even if the transaction fails; retry without new samples.
            session.gear(false, System.currentTimeMillis(), elapsed);
            recorder.endpoint(endWall);
            pendingFinish = store.summary(session, recorder, endWall, elapsed,
                    capacity(EnergyWidgetSettings.BATTERY_KEY, 43), capacity(EnergyWidgetSettings.TANK_KEY, 56));
            pendingFinishKeep = TripSession.shouldSaveHistory(prefs().getBoolean("saveHistory", true),
                    REASON_MANUAL_STOP.equals(reason), session.duration(elapsed));
            pendingFinishReason = reason;
            publishEnergy(); syncLocationService();
            return saveFinishedTrip();
        } catch (Exception e) { Log.e(TAG, "Cannot prepare trip summary; retaining session", e); return false; }
    }
    private boolean saveFinishedTrip() {
        try {
            store.save(session, recorder, pendingFinish, pendingFinishKeep,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime());
            String reason = pendingFinishReason;
            pendingFinish = null; pendingFinishReason = null;
            session = session.afterFinish(); recorder = new TripRecorder();
            refreshHistory(); publishEnergy(); syncLocationService(); broadcastUpdate();
            Log.i(TAG, "Trip finished: " + reason); return true;
        } catch (Exception e) {
            broadcastUpdate();
            Log.e(TAG, "Cannot finish trip; paused until the next save attempt", e); return false;
        }
    }

    private void stopTrip() {
        if (pendingFinish != null) { saveFinishedTrip(); return; }
        if (!session.active) { broadcastUpdate(); return; }
        collect();
        double odo = odometer(SystemClock.elapsedRealtime());
        session.waitForMovement(odo, odometerSource);
        finalizeTrip(REASON_MANUAL_STOP, System.currentTimeMillis());
    }

    private void onTelemetry(CanBusEvent e) {
        if (destroyed) return;
        if (e.kind == CanBusEvent.Kind.GEAR) { gearEventLive = e.origin == CanBusEvent.Origin.LIVE; onGear(e.first); }
        else if (e.kind == CanBusEvent.Kind.CONNECTION || e.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            connected = e.kind == CanBusEvent.Kind.CONNECTION;
            Arrays.fill(received, -1); recorder.breakObservation();
            if (!connected && session.inDrive) {
                session.gear(false, System.currentTimeMillis(), SystemClock.elapsedRealtime());
                lastGear = -1; persistAndBroadcast(); syncLocationService();
            }
            if (connected) { hub.requestEnergySnapshot(); hub.requestGearSnapshot(); }
        } else if (e.telemetry != null) {
            sensors[e.telemetry.kind] = e.telemetry.values[0]; received[e.telemetry.kind] = e.elapsedRealtime;
            collect();
        }
    }
    private double sensor(int kind, long now) {
        return connected && received[kind] >= 0 && now >= received[kind]
                && now - received[kind] <= TripRecorder.MAX_SENSOR_AGE_MS ? sensors[kind] : Double.NaN;
    }
    private void collect() {
        if (!session.inDrive && (!session.waitingForMovement || session.active || pendingFinish != null)) return;
        if (!locationPermissionAllowed()) recorder.clearLocation();
        long now = SystemClock.elapsedRealtime();
        double odo = odometer(now);
        boolean restarted = !session.inDrive && session.movementRestart(odo, odometerSource);
        if (!session.inDrive && !restarted) return;
        if (restarted) {
            session.gear(true, System.currentTimeMillis(), now);
            recorder.levelsAtDrive(sensor(EnergyTelemetrySample.SOC, now), sensor(EnergyTelemetrySample.FUEL, now));
            // Recheck physical gear after the movement fallback; no cached P/D is authoritative.
            lastGear = -1;
        }
        recorder.sample(odo, sensor(EnergyTelemetrySample.SOC, now), sensor(EnergyTelemetrySample.FUEL, now),
                now, System.currentTimeMillis());
        if (!prefs().getBoolean("saveHistory", true)) recorder.pending.clear();
        publishEnergy();
        if (restarted) { hub.requestGearSnapshot(); syncLocationService(); persistAndBroadcast(); }
    }
    private double odometer(long now) {
        double coarse = sensor(EnergyTelemetrySample.ODOMETER, now);
        double ticks = sensor(EnergyTelemetrySample.PRECISE_ODOMETER, now);
        // Cross-check the independently observed raw 100 m odometer against OEM whole kilometres.
        // Whole-km OEM ODO remains useful for totals, but cannot provide a 100 m GPS cadence.
        // Never substitute GPS distance or the resettable OEM trip counter.
        boolean precise = TripRecorder.preciseOdometerCompatible(coarse, ticks);
        int source = precise ? 2 : Double.isFinite(coarse) ? 1 : 0;
        if (source != odometerSource) { recorder.breakObservation(); odometerSource = source; }
        if (!precise) recorder.clearLocation();
        return source == 2 ? ticks * 100 : source == 1 ? coarse * 1000 : Double.NaN;
    }
    private void publishEnergy() {
        liveEnergy = new double[]{session.active ? recorder.distanceMeters / 1000 : 0,
                recorder.evDrop, recorder.fuelDrop, recorder.evMeters / 1000, recorder.fuelMeters / 1000};
    }
    private float capacity(String key, float fallback) {
        float value = getSharedPreferences("EnergyWidgetHistory", MODE_PRIVATE).getFloat(key, fallback);
        return EnergyWidgetSettings.validCapacity(value) ? value : fallback;
    }
    private SharedPreferences prefs() { return getSharedPreferences("TripStats", MODE_PRIVATE); }
    private void refreshHistory() {
        try { historyJson = store.summaries(); } catch (Exception e) { Log.w(TAG, "Cannot read history", e); }
    }
    private void scheduleCanStatePublish() {
        canStatePublishPending = true; timerHandler.removeCallbacks(canStatePublishRunnable);
        timerHandler.postDelayed(canStatePublishRunnable, CAN_STATE_PUBLISH_COALESCE_MS);
    }
    private void persistState() {
        if (pendingFinish != null) { saveFinishedTrip(); return; }
        try { store.save(session, recorder, null, false, System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
        catch (Exception e) { Log.e(TAG, "Cannot checkpoint trip", e); }
    }
    private void persistAndBroadcast() {
        canStatePublishPending = false; timerHandler.removeCallbacks(canStatePublishRunnable);
        persistState(); broadcastUpdate();
    }
    private void broadcastUpdate() {
        Intent i = new Intent(ACTION_TRIP_UPDATE).setPackage(TripProtocol.UI);
        i.putExtra(EXTRA_TRIP_ACTIVE, session.active); i.putExtra(EXTRA_IN_DRIVE, session.inDrive);
        i.putExtra(TripProtocol.WAITING_FOR_MOVEMENT, session.waitingForMovement);
        i.putExtra(EXTRA_ACCUM_MS, session.accumulatedMs); i.putExtra(EXTRA_DRIVE_START, session.driveStartElapsed);
        i.putExtra("tripStartWall", session.startWall); i.putExtra(EXTRA_TRIPS_JSON, historyJson);
        sendBroadcast(i, TripProtocol.PERMISSION);
    }
    private void syncLocationService() {
        Intent i = new Intent().setClassName(TripProtocol.UI, TripProtocol.LOCATION_SERVICE);
        try {
            android.os.Bundle consent = getContentResolver().call(android.net.Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider"),
                    TripProtocol.LOCATION_PERMISSION_STATE, null, null);
            trackingConsent = consent != null && consent.getBoolean("allowed", false);
        } catch (RuntimeException unavailable) { trackingConsent = false; }
        boolean allowed = locationPermissionAllowed();
        if (!allowed) recorder.clearLocation();
        try {
            if (session.inDrive && allowed && prefs().getBoolean("saveHistory", true)) {
                i.putExtra("tripStartWall", session.startWall); startForegroundService(i);
            } else stopService(i);
        } catch (RuntimeException e) { Log.w(TAG, "GPS service unavailable", e); }
    }
    private boolean locationPermissionAllowed() {
        return trackingConsent && getPackageManager().checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, TripProtocol.UI) == PackageManager.PERMISSION_GRANTED
                && getPackageManager().checkPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, TripProtocol.UI) == PackageManager.PERMISSION_GRANTED;
    }
    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Intent request = new Intent(intent);
            timerHandler.post(() -> {
                if (destroyed) return;
                String action = request.getAction();
                if (ACTION_TRIP_STOP.equals(action)) stopTrip();
                else if (ACTION_TRIP_DELETE.equals(action)) {
                    try {
                        if (request.getBooleanExtra(TripProtocol.DELETE_ALL, false)) store.clearHistory();
                        else {
                            long start = request.getLongExtra(EXTRA_DELETE_START, -1);
                            if (start > 0 && start != session.startWall) store.delete(start);
                        }
                    } catch (android.database.sqlite.SQLiteException failure) {
                        Log.e(TAG, "Cannot delete trip history", failure);
                    }
                    refreshHistory(); broadcastUpdate();
                } else if (ACTION_TRIP_HISTORY.equals(action)) {
                    boolean enabled = request.getBooleanExtra("enabled", true);
                    prefs().edit().putBoolean("saveHistory", enabled).apply();
                    if (!enabled) { store.clearHistory(); recorder.pending.clear(); pendingFinishKeep = false; }
                    refreshHistory(); syncLocationService(); broadcastUpdate();
                } else if (TripProtocol.LOCATION.equals(action)) {
                    if (session.inDrive && request.getLongExtra("tripStartWall", 0) == session.startWall) {
                        recorder.location(request.getDoubleExtra("lat", Double.NaN), request.getDoubleExtra("lon", Double.NaN),
                                request.getFloatExtra("accuracy", Float.NaN), request.getLongExtra("elapsed", -1),
                                SystemClock.elapsedRealtime(), request.getBooleanExtra("mock", true));
                    }
                } else { syncLocationService(); broadcastUpdate(); }
            });
        }
    };
    private final BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (!destroyed && store != null) persistState(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Статистика поездок", NotificationManager.IMPORTANCE_MIN));
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Статистика поездок")
                .setContentText("Учёт времени в пути").setSmallIcon(R.drawable.ic_launcher_foreground).build();
        startForeground(4, n);
        thread = new HandlerThread("TripStats"); thread.start(); timerHandler = new Handler(thread.getLooper());
        timerHandler.post(() -> {
            store = new TripStore(this);
            Arrays.fill(received, -1); Arrays.fill(sensors, Float.NaN);
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            try {
                if (!store.restore(session, recorder)) {
                    // Import the former timer/log once; elapsed timestamps without boot identity are unusable.
                    SharedPreferences p = prefs();
                    if (!p.getBoolean("tripStoreImported", false)) {
                        store.importHistory(p.getString("tripsJson", "[]"));
                        session.active = p.getBoolean("curActive", false); session.startWall = p.getLong("curStartWall", 0);
                        session.accumulatedMs = Math.max(0, p.getLong("curAccumMs", 0));
                        session.pausedWall = System.currentTimeMillis();
                        store.save(session, recorder, null, false, System.currentTimeMillis(), SystemClock.elapsedRealtime());
                        p.edit().putBoolean("tripStoreImported", true).remove("tripsJson").remove("curActive")
                                .remove("curInDrive").remove("curAccumMs").remove("curDriveStart").remove("curStartWall").remove("lastGear").apply();
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Cannot restore trip", e); session = new TripSession(); recorder = new TripRecorder(); }
            if (session.active && session.inDrive) recorder.resumeOdo = recorder.currentOdo;
            session.recover(boot); refreshHistory(); publishEnergy(); broadcastUpdate();
            hub = CanBusEventHub.get(this);
            telemetrySubscription = hub.subscribe(CanBusEventRouter.INTEREST_CONNECTION | CanBusEventRouter.INTEREST_ENERGY_TELEMETRY | CanBusEventRouter.INTEREST_GEAR,
                    null, timerHandler, this::onTelemetry);
            VehicleStateControllers v = VehicleStateControllers.get(this);
            driverDoorSubscription = v.driverDoor().subscribe(timerHandler, this::onDoor);
            timerHandler.post(checkpointRunnable);
        });
        IntentFilter f = new IntentFilter(ACTION_REQUEST_TRIP_UPDATE);
        f.addAction(ACTION_TRIP_STOP); f.addAction(ACTION_TRIP_DELETE); f.addAction(ACTION_TRIP_HISTORY);
        f.addAction(TripProtocol.LOCATION); f.addAction(TripProtocol.LOCATION_CHANGED);
        ContextCompat.registerReceiver(this, requestReceiver, f, TripProtocol.PERMISSION, timerHandler, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, shutdownReceiver, new IntentFilter(Intent.ACTION_SHUTDOWN), null, timerHandler, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Physical wake and Android/service restart are never trip boundaries.
        if (intent != null && ACTION_POWER_ON.equals(intent.getAction()))
            timerHandler.post(() -> { if (!destroyed && hub != null) hub.requestGearSnapshot(); });
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(shutdownReceiver); } catch (Exception ignored) { }
        timerHandler.post(() -> {
            destroyed = true;
            if (canStatePublishPending) { timerHandler.removeCallbacks(canStatePublishRunnable); canStatePublishPending = false; }
            persistState();
            if (driverDoorSubscription != null) driverDoorSubscription.close();
            if (telemetrySubscription != null) telemetrySubscription.close();
            timerHandler.removeCallbacksAndMessages(null); store.close(); thread.quitSafely();
        });
        super.onDestroy();
    }
}
