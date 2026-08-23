package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    // Через это время после коннекта форсируем queryVehicleState (снимок статусов).
    private static final long FORCE_QUERY_MS = 6_000L;
    // Пока initial snapshot неполон, не повторяем тяжёлый full query чаще этого интервала.
    private static final long FULL_QUERY_MIN_INTERVAL_MS = 5 * 60_000L;
    // Анти-спам активации: не пытаемся включать прогрев чаще, чем раз в эти мс.
    private static final long ACTIVATE_REARM_MS = 5 * 60_000L;
    private static final long ACTIVATE_FAILURE_RETRY_MS = 30_000L;
    private static final long SETTINGS_RETRY_MS = 5_000L;
    private static final long BROADCAST_COALESCE_MS = 250L;

    private static final AtomicLong INSTANCE_SEQUENCE = new AtomicLong();
    private static final AtomicLong ACTIVE_INSTANCE = new AtomicLong();
    private static final AtomicLong BROADCAST_REVISION = new AtomicLong();
    private static final ThreadPoolExecutor SETTINGS_EXECUTOR =
            newBoundedExecutor("BatteryHeatSettings");
    private static final ThreadPoolExecutor BROADCAST_EXECUTOR =
            newBoundedExecutor("BatteryHeatBroadcast");
    private static final LatestValueDelivery<BroadcastWrite> BROADCASTS =
            new LatestValueDelivery<>(BROADCAST_EXECUTOR,
                    BatteryHeatService::sendSnapshotBroadcast);

    private static ThreadPoolExecutor newBoundedExecutor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class BroadcastWrite {
        final Context app;
        final long generation;
        final Intent intent;

        BroadcastWrite(Context app, long generation, Intent intent) {
            this.app = app;
            this.generation = generation;
            this.intent = intent;
        }
    }

    // value-ID сигналов ВВБ (VehicleState.value), проверены по декомпиляции H97C
    private static final int ID_TEP_CONTROL_SWITCH = 1294; // 1 вкл, 2 выкл
    private static final int ID_TEP_CONTROL_STATUS = 1295; // 0 неактивен, 1 активен(греется), 2 инициализация, 3 резерв
    private static final int ID_TEP_CONTROL_FAIL   = 1296; // причина отказа (см. failText)
    private static final int ID_AUTO_CTRL          = 1298; // авто-термоконтроль ВВБ: 1 вкл, 2 выкл
    private static final int ID_AUTO_CTRL_INFO     = 1299; // инфо-код авто-режима (0..3)
    private static final int ID_DRIVER_PREHEAT_SET = 1080; // предпусковой прогрев (set)
    private static final int ID_PREHEAT_FAIL_STATE = 1265; // причина отказа прогрева (те же коды, что fail)
    private static final int ID_BMS_STATE          = 958;  // 9 = PREHEAT

    // Логические поля snapshot; оба fail-ID считаются одним полем-источником причины отказа.
    private static final int FIELD_SWITCH       = 0;
    private static final int FIELD_STATUS       = 1;
    private static final int FIELD_FAIL         = 2;
    private static final int FIELD_AUTO_CTRL    = 3;
    private static final int FIELD_AUTO_INFO    = 4;
    private static final int FIELD_PREHEAT_SET  = 5;
    private static final int FIELD_BMS_STATE    = 6;
    private static final int VEHICLE_FIELD_COUNT = 7;
    private static final int ALL_VEHICLE_FIELDS_MASK = (1 << VEHICLE_FIELD_COUNT) - 1;

    // Значение «неизвестно» для статусов, которых ещё не приходило
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private volatile Handler handler;
    private HandlerThread workerThread;
    private boolean receiverRegistered;
    private boolean broadcastScheduled;
    private final BatteryHeatRefreshGate refreshGate = new BatteryHeatRefreshGate();
    private long instanceGeneration;
    private boolean cachedAutoEnabled;
    private volatile long activeCanBusEpoch;
    private long ambientTempEpoch;

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
    private long lastActivateAttemptElapsed = Long.MIN_VALUE / 2;
    private boolean activationPending = false;

    private CanBusEventHub canBusEventHub;
    private CanBusEventHub.Subscription canBusSubscription;
    private volatile boolean destroyed = false;
    private int vehicleFieldsSeenMask = 0;
    private long lastFullQueryAttemptElapsed = Long.MIN_VALUE / 2;

    private final Runnable forceQueryRunnable = this::requestVehicleStateSnapshotIfIncomplete;

    private void onCanBusEvent(CanBusEvent event) {
        if (destroyed) return;
        switch (event.kind) {
            case CONNECTION:
                activeCanBusEpoch = event.connectionEpoch;
                resetVehicleSnapshotTracking();
                // Poll must not overtake the mandatory delayed snapshot for this fresh epoch.
                lastFullQueryAttemptElapsed = SystemClock.elapsedRealtime();
                handler.removeCallbacks(forceQueryRunnable);
                handler.postDelayed(forceQueryRunnable, FORCE_QUERY_MS);
                break;
            case VEHICLE_STATE:
                onVehicleState(event.first, event.second);
                break;
            case AMBIENT_TEMPERATURE:
                onAmbientTemp(event.first, event.connectionEpoch);
                break;
            default:
                break;
        }
    }

    /** Requests a filtered callback snapshot; TX20 itself runs on the hub query thread. */
    private void requestVehicleStateSnapshotIfIncomplete() {
        if (destroyed || canBusEventHub == null || !isVehicleSnapshotIncomplete()) return;
        lastFullQueryAttemptElapsed = SystemClock.elapsedRealtime();
        canBusEventHub.requestVehicleStateSnapshot();
        Log.i(TAG, "queryVehicleState requested, fields="
                + Integer.bitCount(vehicleFieldsSeenMask) + "/" + VEHICLE_FIELD_COUNT);
    }

    // -------------------------------------------------------------------------
    // Обработка входящих данных
    // -------------------------------------------------------------------------

    private void onVehicleState(int id, int state) {
        final int field;
        switch (id) {
            case ID_TEP_CONTROL_SWITCH:
                switchState = state;
                field = FIELD_SWITCH;
                break;
            case ID_TEP_CONTROL_STATUS:
                controlStatus = state;
                field = FIELD_STATUS;
                break;
            case ID_TEP_CONTROL_FAIL:
                failReason = state;
                field = FIELD_FAIL;
                break;
            case ID_AUTO_CTRL:
                autoCtrl = state;
                field = FIELD_AUTO_CTRL;
                break;
            case ID_AUTO_CTRL_INFO:
                autoCtrlInfo = state;
                field = FIELD_AUTO_INFO;
                break;
            case ID_DRIVER_PREHEAT_SET:
                preheatSet = state;
                field = FIELD_PREHEAT_SET;
                break;
            case ID_PREHEAT_FAIL_STATE:
                // Резервный источник причины отказа, если основной (1296) не приходит.
                if (failReason == UNKNOWN || failReason == 0) failReason = state;
                field = FIELD_FAIL;
                break;
            case ID_BMS_STATE:
                bmsState = state;
                field = FIELD_BMS_STATE;
                break;
            default: return; // не наш сигнал
        }
        vehicleFieldsSeenMask |= 1 << field;
        Log.i(TAG, "vehicleState id=" + id + " state=" + state);
        requestBroadcastUpdate();
    }

    private void onAmbientTemp(int t, long epoch) {
        if (t == ambientTemp && epoch == ambientTempEpoch) return;
        ambientTemp = t;
        ambientTempEpoch = epoch;
        Log.i(TAG, "ambientTemp=" + t + "°C");
        requestBroadcastUpdate();
        requestSettingsRefresh("temp-change", true);
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
    private void maybeAutoActivate(String src, boolean autoEnabled) {
        if (!isActiveInstance()) return;
        if (ambientTempEpoch != activeCanBusEpoch) return;
        if (!autoEnabled) return;
        if (ambientTemp == TEMP_INVALID) return;
        if (ambientTemp >= AUTO_TEMP_THRESHOLD_C) return;
        if (controlStatus == 1) return; // уже греется
        long now = SystemClock.elapsedRealtime();
        if (now - lastActivateElapsed < ACTIVATE_REARM_MS) return;
        if (now - lastActivateAttemptElapsed < ACTIVATE_FAILURE_RETRY_MS) return;
        if (activationPending) return;
        Log.i(TAG, "AUTO прогрев: " + src + " ambient=" + ambientTemp + "°C < " + AUTO_TEMP_THRESHOLD_C);
        activate("auto <" + AUTO_TEMP_THRESHOLD_C + "°C", false);
    }

    /** Активация прогрева. Реальную CAN-команду задаёт пользователь в {@link MainActivity#sendBatteryHeatCommand()}. */
    private void activate(String reason, boolean explicitUserAction) {
        if (destroyed) return;
        if (activationPending) {
            Log.i(TAG, "activate battery heat coalesced — " + reason);
            return;
        }
        activationPending = true;
        Log.i(TAG, "★ activate battery heat — " + reason);
        if (explicitUserAction) {
            ApplyEngine.postIndependentUserCommand("battery heat " + reason, () -> {
                final long attemptedAt = SystemClock.elapsedRealtime();
                boolean sent = false;
                try {
                    sent = MainActivity.sendBatteryHeatCommand();
                } finally {
                    final ApplyEngine.WakeActionResult result = sent
                            ? ApplyEngine.WakeActionResult.SUCCESS
                            : ApplyEngine.WakeActionResult.FAILED;
                    Handler worker = handler;
                    if (!destroyed && worker != null) {
                        worker.post(() -> finishActivation(result, attemptedAt));
                    }
                }
            });
        } else {
            final long activationGeneration = instanceGeneration;
            final long activationCanBusEpoch = activeCanBusEpoch;
            final long[] attemptedAt = {0L};
            ApplyEngine.postWakeAction("battery heat " + reason, () -> {
                if (destroyed || ACTIVE_INSTANCE.get() != activationGeneration
                        || activeCanBusEpoch != activationCanBusEpoch) {
                    return false;
                }
                attemptedAt[0] = SystemClock.elapsedRealtime();
                return MainActivity.sendBatteryHeatCommand();
            }, result -> {
                final long attempt = attemptedAt[0];
                Handler worker = handler;
                if (!destroyed && worker != null) {
                    worker.post(() -> finishActivation(result, attempt));
                }
            });
        }
    }

    private void finishActivation(ApplyEngine.WakeActionResult result, long attemptedAt) {
        if (destroyed) return;
        activationPending = false;
        if (result == ApplyEngine.WakeActionResult.SKIPPED) return;

        lastActivateAttemptElapsed = attemptedAt;
        if (result == ApplyEngine.WakeActionResult.SUCCESS) {
            lastActivateElapsed = attemptedAt;
        } else {
            Log.w(TAG, "battery heat CAN failed; retry is allowed after "
                    + ACTIVATE_FAILURE_RETRY_MS + "ms");
        }
    }

    // -------------------------------------------------------------------------
    // ContentProvider — настройка «Автоматический прогрев батареи» (колонка 17).
    // Query is synchronous Binder work: AUTO decisions and explicit UI refreshes use a bounded worker.
    // -------------------------------------------------------------------------

    private static final Uri CONTENT_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    private static final int COL_BATTERY_HEAT_AUTO = 17;

    private void requestSettingsRefresh(String reason, boolean evaluateAuto) {
        if (destroyed) return;
        BatteryHeatRefreshGate.Request start = refreshGate.offer(
                activeCanBusEpoch, evaluateAuto, reason);
        if (start != null) submitSettingsRefresh(start);
    }

    private void submitSettingsRefresh(BatteryHeatRefreshGate.Request request) {
        if (destroyed || request == null) return;
        ContentResolver resolver = getApplicationContext().getContentResolver();
        WeakReference<BatteryHeatService> serviceRef = new WeakReference<>(this);
        try {
            SETTINGS_EXECUTOR.execute(() -> {
                BatteryHeatService beforeQuery = serviceRef.get();
                if (beforeQuery == null || beforeQuery.destroyed) return;
                boolean enabled = queryAutoEnabled(resolver);
                BatteryHeatService service = serviceRef.get();
                if (service == null || service.destroyed) return;
                Handler worker = service.handler;
                if (worker != null) {
                    worker.post(() -> service.finishSettingsRefresh(request, enabled));
                }
            });
        } catch (RejectedExecutionException e) {
            refreshGate.reject(request);
            scheduleSettingsRetry();
        }
    }

    private void finishSettingsRefresh(BatteryHeatRefreshGate.Request request, boolean enabled) {
        if (destroyed) return;
        BatteryHeatRefreshGate.Completion completion = refreshGate.finish(request);
        if (completion.publish) {
            cachedAutoEnabled = enabled;
            requestBroadcastUpdate();
            // State is read only now, on the service worker; no captured snapshot triggers an action.
            if (request.evaluateAuto && request.epoch == activeCanBusEpoch
                    && isActiveInstance()) {
                maybeAutoActivate(request.reason, enabled);
            }
        }
        if (completion.next != null) submitSettingsRefresh(completion.next);
    }

    private void scheduleSettingsRetry() {
        if (destroyed) return;
        Handler worker = handler;
        if (worker == null) return;
        worker.removeCallbacks(settingsRetryRunnable);
        worker.postDelayed(settingsRetryRunnable, SETTINGS_RETRY_MS);
    }

    private final Runnable settingsRetryRunnable = () -> {
        if (destroyed) return;
        BatteryHeatRefreshGate.Request retry = refreshGate.retry();
        if (retry != null) submitSettingsRefresh(retry);
    };

    private static boolean queryAutoEnabled(ContentResolver resolver) {
        try {
            Cursor c = resolver.query(CONTENT_PROVIDER_URI, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && c.getColumnCount() > COL_BATTERY_HEAT_AUTO)
                        return c.getInt(COL_BATTERY_HEAT_AUTO) == 1;
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryAutoEnabled: " + e.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Broadcast снимка в UI
    // -------------------------------------------------------------------------

    private void requestBroadcastUpdate() {
        if (destroyed || broadcastScheduled) return;
        Handler worker = handler;
        if (worker == null) return;
        broadcastScheduled = true;
        if (!worker.postDelayed(broadcastRunnable, BROADCAST_COALESCE_MS)) {
            broadcastScheduled = false;
        }
    }

    private final Runnable broadcastRunnable = () -> {
        broadcastScheduled = false;
        if (!destroyed) enqueueBroadcastUpdate();
    };

    private void enqueueBroadcastUpdate() {
        Intent i = new Intent(ACTION_BATTERY_HEAT_UPDATE);
        i.putExtra("ambientTemp",   ambientTemp);
        i.putExtra("controlStatus", controlStatus);
        i.putExtra("switchState",   switchState);
        i.putExtra("failReason",    failReason);
        i.putExtra("autoCtrl",      autoCtrl);
        i.putExtra("autoCtrlInfo",  autoCtrlInfo);
        i.putExtra("preheatSet",    preheatSet);
        i.putExtra("bmsState",      bmsState);
        i.putExtra("autoEnabled",   cachedAutoEnabled ? 1 : 0);
        i.putExtra("tempThreshold", AUTO_TEMP_THRESHOLD_C);
        Context app = getApplicationContext();
        BROADCASTS.offer(instanceGeneration, BROADCAST_REVISION.incrementAndGet(),
                new BroadcastWrite(app, instanceGeneration, i));
    }

    private static void sendSnapshotBroadcast(BroadcastWrite request) {
        if (request == null || ACTIVE_INSTANCE.get() != request.generation) return;
        try {
            request.app.sendBroadcast(request.intent);
        } catch (Exception e) {
            Log.w(TAG, "broadcastUpdate: " + e.getMessage());
        }
    }

    private final BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_BATTERY_HEAT_ACTIVATE.equals(a)) {
                // Ручная активация из виджета (в один клик).
                activate("manual (виджет)", true);
            } else {
                // ACTION_REQUEST_BATTERY_HEAT only asks for the current cached snapshot. The
                // auto setting is used for decisions, not as confirmation before a UI response.
                requestBroadcastUpdate();
            }
        }
    };

    private boolean isActiveInstance() {
        return !destroyed && ACTIVE_INSTANCE.get() == instanceGeneration;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate() — BatteryHeatService");
        instanceGeneration = INSTANCE_SEQUENCE.incrementAndGet();
        ACTIVE_INSTANCE.set(instanceGeneration);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Прогрев батареи")
                .setContentText("Мониторинг температуры и статуса ВВБ")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(5, notification);

        workerThread = new HandlerThread("BatteryHeat", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        handler.post(this::initializeMonitoring);
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
        destroyed = true;
        ACTIVE_INSTANCE.compareAndSet(instanceGeneration, 0L);
        refreshGate.close();
        Handler worker = handler;
        HandlerThread thread = workerThread;
        if (worker != null && thread != null) {
            boolean queued = worker.postAtFrontOfQueue(() -> {
                try {
                    CanBusEventHub.Subscription subscription = canBusSubscription;
                    canBusSubscription = null;
                    if (subscription != null) subscription.close();
                    canBusEventHub = null;
                    if (receiverRegistered) {
                        try { unregisterReceiver(uiReceiver); } catch (Exception ignored) {}
                        receiverRegistered = false;
                    }
                } finally {
                    worker.removeCallbacksAndMessages(null);
                    handler = null;
                    thread.quitSafely();
                }
            });
            if (!queued) thread.quitSafely();
        }
        super.onDestroy();
    }

    private void initializeMonitoring() {
        if (destroyed) return;
        IntentFilter filter = new IntentFilter(ACTION_REQUEST_BATTERY_HEAT);
        filter.addAction(ACTION_BATTERY_HEAT_ACTIVATE);
        try {
            registerReceiver(uiReceiver, filter, null, handler, RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver: " + e.getMessage());
        }
        if (destroyed) return;
        canBusEventHub = CanBusEventHub.get(this);
        canBusSubscription = canBusEventHub.subscribe(
                CanBusEventRouter.INTEREST_CONNECTION
                        | CanBusEventRouter.INTEREST_AMBIENT_TEMPERATURE
                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{ID_TEP_CONTROL_SWITCH, ID_TEP_CONTROL_STATUS,
                        ID_TEP_CONTROL_FAIL, ID_AUTO_CTRL, ID_AUTO_CTRL_INFO,
                        ID_DRIVER_PREHEAT_SET, ID_PREHEAT_FAIL_STATE, ID_BMS_STATE},
                handler, this::onCanBusEvent);
        handler.postDelayed(pollRunnable, 2_000L);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (isVehicleSnapshotIncomplete()
                    && now - lastFullQueryAttemptElapsed >= FULL_QUERY_MIN_INTERVAL_MS) {
                requestVehicleStateSnapshotIfIncomplete();
            }
            requestBroadcastUpdate();
            requestSettingsRefresh("poll", true);
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void resetVehicleSnapshotTracking() {
        vehicleFieldsSeenMask = 0;
    }

    private boolean isVehicleSnapshotIncomplete() {
        // Once the initial snapshot is complete, live callbacks are the source of truth. Polling
        // TX20 merely to confirm unchanged values adds global Binder/CAN fan-out with no new state.
        return vehicleFieldsSeenMask != ALL_VEHICLE_FIELDS_MASK;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Прогрев батареи", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
