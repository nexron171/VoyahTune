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
import androidx.core.content.ContextCompat;

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
    public static final String ACTION_BATTERY_HEAT_AUTO_CHANGED =
            "ru.big.town.anative.BATTERY_HEAT_AUTO_CHANGED";
    public static final String EXTRA_BATTERY_HEAT_AUTO_ENABLED = "autoEnabled";
    private static final String ACTION_STARTUP_SETTINGS_REFRESH =
            "ru.big.town.anative.BATTERY_HEAT_STARTUP_SETTINGS_REFRESH";
    private static final String ACTION_PHYSICAL_WAKE_SETTINGS_REFRESH =
            "ru.big.town.anative.BATTERY_HEAT_PHYSICAL_WAKE_SETTINGS_REFRESH";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    // Порог автоматического прогрева: ниже этой уличной температуры (°C) включаем прогрев.
    private static final int AUTO_TEMP_THRESHOLD_C = 10;
    // Значение уличной температуры, трактуемое как «нет данных» (так отдаёт CanBusService).
    private static final int TEMP_INVALID = -9999;

    // Independent internal watchdog. Its cadence does not depend on incoming CAN callbacks: it
    // keeps automatic activation recoverable after a missed event/failed frame without allowing
    // an input storm to multiply work.
    private static final long BATTERY_SAFETY_WATCHDOG_MS = 30_000L;
    private static final long INCOMPLETE_SNAPSHOT_RETRY_MS = 5 * 60_000L;
    // Через это время после коннекта форсируем queryVehicleState (снимок статусов).
    private static final long FORCE_QUERY_MS = 6_000L;
    // Анти-спам активации: не пытаемся включать прогрев чаще, чем раз в эти мс.
    private static final long ACTIVATE_REARM_MS = 5 * 60_000L;
    private static final long ACTIVATE_FAILURE_RETRY_MS = 30_000L;
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
    private boolean startupRefreshRequested;
    private final BatteryHeatRefreshGate refreshGate = new BatteryHeatRefreshGate();
    private long instanceGeneration;
    private volatile boolean cachedAutoEnabled;
    private volatile boolean autoSettingKnown;
    private volatile long autoSettingRevision;
    private volatile long autoDecisionGeneration;
    private volatile long activeCanBusEpoch;
    private volatile long ambientTempEpoch;

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
    private long lastVehicleSnapshotRequestElapsed = Long.MIN_VALUE / 2;

    private CanBusEventHub canBusEventHub;
    private CanBusEventHub.Subscription canBusSubscription;
    private volatile boolean destroyed = false;
    private int vehicleFieldsSeenMask = 0;

    private final Runnable forceQueryRunnable = this::requestVehicleStateSnapshot;

    private void onCanBusEvent(CanBusEvent event) {
        if (destroyed) return;
        switch (event.kind) {
            case CONNECTION:
                activeCanBusEpoch = event.connectionEpoch;
                advanceAutoDecision();
                resetVehicleSnapshotTracking();
                // Exactly one delayed snapshot request belongs to this fresh connection epoch.
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
    private void requestVehicleStateSnapshot() {
        if (destroyed || canBusEventHub == null || !isVehicleSnapshotIncomplete()) return;
        lastVehicleSnapshotRequestElapsed = SystemClock.elapsedRealtime();
        canBusEventHub.requestVehicleStateSnapshot();
        Log.i(TAG, "queryVehicleState requested, fields="
                + Integer.bitCount(vehicleFieldsSeenMask) + "/" + VEHICLE_FIELD_COUNT);
    }

    // -------------------------------------------------------------------------
    // Обработка входящих данных
    // -------------------------------------------------------------------------

    private void onVehicleState(int id, int state) {
        final int field;
        boolean controlStatusChanged = false;
        switch (id) {
            case ID_TEP_CONTROL_SWITCH:
                switchState = state;
                field = FIELD_SWITCH;
                break;
            case ID_TEP_CONTROL_STATUS:
                controlStatusChanged = controlStatus != state;
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
        if (controlStatusChanged) {
            advanceAutoDecision();
        }
        Log.i(TAG, "vehicleState id=" + id + " state=" + state);
        requestBroadcastUpdate();
        if (controlStatusChanged) {
            maybeAutoActivate("control-status");
        }
    }

    private void onAmbientTemp(int t, long epoch) {
        if (t == ambientTemp && epoch == ambientTempEpoch) return;
        ambientTemp = t;
        ambientTempEpoch = epoch;
        advanceAutoDecision();
        Log.i(TAG, "ambientTemp=" + t + "°C");
        requestBroadcastUpdate();
        if (BatteryHeatAutoPolicy.settingRefreshNeededForTemperature(autoSettingKnown)) {
            // A failed startup/wake read gets another bounded chance on a real temperature event.
            requestSettingsRefresh("temp-change", true);
        } else {
            maybeAutoActivate("temp-change");
        }
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
        final long activationEpoch = activeCanBusEpoch;
        final long activationDecision = autoDecisionGeneration;
        if (!automaticActivationCurrent(
                instanceGeneration, activationEpoch, activationDecision)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastActivateElapsed < ACTIVATE_REARM_MS) return;
        if (now - lastActivateAttemptElapsed < ACTIVATE_FAILURE_RETRY_MS) return;
        if (activationPending) return;
        Log.i(TAG, "AUTO прогрев: " + src + " ambient=" + ambientTemp + "°C < " + AUTO_TEMP_THRESHOLD_C);
        activate("auto <" + AUTO_TEMP_THRESHOLD_C + "°C", false,
                activationEpoch, activationDecision);
    }

    /** Активация прогрева. Реальную CAN-команду задаёт пользователь в {@link MainActivity#sendBatteryHeatCommand()}. */
    private void activate(String reason, boolean explicitUserAction,
                          long automaticEpoch, long automaticDecision) {
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
                        worker.post(() -> finishActivation(
                                result, attemptedAt, Long.MIN_VALUE));
                    }
                }
            });
        } else {
            final long activationGeneration = instanceGeneration;
            final AtomicLong attemptedAt = new AtomicLong();
            ApplyEngine.postWakeAction("battery heat " + reason, () -> {
                // ApplyEngine already installs its physical-wake guard. This nested guard adds the
                // current automatic-decision fence to the same ThreadLocal and is therefore checked
                // immediately before every frame in MainActivity.sendBatteryHeatCommand().
                return CanSender.runGuardedSend(
                        () -> automaticActivationCurrent(
                                activationGeneration, automaticEpoch, automaticDecision),
                        () -> attemptedAt.compareAndSet(
                                0L, SystemClock.elapsedRealtime()),
                        MainActivity::sendBatteryHeatCommand);
            }, result -> {
                final long attempt = attemptedAt.get();
                Handler worker = handler;
                if (!destroyed && worker != null) {
                    worker.post(() -> finishActivation(
                            result, attempt, automaticDecision));
                }
            });
        }
    }

    private void finishActivation(ApplyEngine.WakeActionResult result, long attemptedAt,
                                  long automaticDecision) {
        if (destroyed) return;
        activationPending = false;
        if (attemptedAt > 0L) {
            // The physical-wake guard may turn false after a failed JNI transaction and make the
            // terminal result SKIPPED. The exact per-frame marker still proves a real bus attempt,
            // so it must retain the failure cooldown and prevent an immediate duplicate command.
            lastActivateAttemptElapsed = attemptedAt;
            if (result == ApplyEngine.WakeActionResult.SUCCESS) {
                lastActivateElapsed = attemptedAt;
            }
        }

        if (automaticDecision != Long.MIN_VALUE
                && automaticDecision != autoDecisionGeneration) {
            // Exactly one completion handoff to the latest event decision. No timer/retry is armed;
            // anti-spam timestamps still apply if any frame was actually attempted.
            maybeAutoActivate("stale-completion-handoff");
            return;
        }
        if (result == ApplyEngine.WakeActionResult.SKIPPED) return;
        if (result != ApplyEngine.WakeActionResult.SUCCESS) {
            Log.w(TAG, "battery heat CAN failed; next qualifying event after "
                    + ACTIVATE_FAILURE_RETRY_MS + "ms may retry");
        }
    }

    private void advanceAutoDecision() {
        ++autoDecisionGeneration;
    }

    private boolean automaticActivationCurrent(long activationInstance,
                                               long activationEpoch,
                                               long activationDecision) {
        return BatteryHeatAutoPolicy.canSend(
                !destroyed && ACTIVE_INSTANCE.get() == activationInstance,
                activationEpoch, activeCanBusEpoch, ambientTempEpoch,
                activationDecision, autoDecisionGeneration,
                cachedAutoEnabled,
                ambientTemp != TEMP_INVALID,
                ambientTemp < AUTO_TEMP_THRESHOLD_C,
                controlStatus == 1);
    }

    // -------------------------------------------------------------------------
    // ContentProvider — настройка «Автоматический прогрев батареи» (колонка 17).
    // Query is synchronous Binder work: startup/wake reconciliation and unknown-setting recovery
    // use a bounded worker. Ordinary temperature/UI events use the authoritative in-memory cache.
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
        final long submittedSettingRevision = autoSettingRevision;
        try {
            SETTINGS_EXECUTOR.execute(() -> {
                BatteryHeatService beforeQuery = serviceRef.get();
                if (beforeQuery == null || beforeQuery.destroyed) return;
                if (!BatteryHeatAutoPolicy.revisionCurrent(
                        submittedSettingRevision, beforeQuery.autoSettingRevision)) {
                    Handler staleWorker = beforeQuery.handler;
                    if (staleWorker != null) {
                        staleWorker.post(() -> beforeQuery.finishSettingsRefresh(
                                request, null, submittedSettingRevision));
                    }
                    return;
                }
                Boolean enabled = queryAutoEnabled(resolver);
                BatteryHeatService service = serviceRef.get();
                if (service == null || service.destroyed) return;
                Handler worker = service.handler;
                if (worker != null) {
                    worker.post(() -> service.finishSettingsRefresh(
                            request, enabled, submittedSettingRevision));
                }
            });
        } catch (RejectedExecutionException e) {
            refreshGate.reject(request);
            Log.w(TAG, "settings refresh queue full; waiting for next real event");
        }
    }

    private void finishSettingsRefresh(BatteryHeatRefreshGate.Request request, Boolean enabled,
                                       long submittedSettingRevision) {
        if (destroyed) return;
        BatteryHeatRefreshGate.Completion completion = refreshGate.finish(request);
        if (completion.publish && BatteryHeatAutoPolicy.revisionCurrent(
                submittedSettingRevision, autoSettingRevision) && enabled != null) {
            if (!autoSettingKnown || cachedAutoEnabled != enabled) {
                autoSettingKnown = true;
                cachedAutoEnabled = enabled;
                advanceAutoDecision();
            }
            requestBroadcastUpdate();
            // State is read only now, on the service worker; no captured snapshot triggers an action.
            if (request.evaluateAuto && request.epoch == activeCanBusEpoch
                    && isActiveInstance()) {
                maybeAutoActivate(request.reason);
            }
        }
        if (completion.next != null) submitSettingsRefresh(completion.next);
    }

    private static Boolean queryAutoEnabled(ContentResolver resolver) {
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
        return null;
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
                activate("manual (виджет)", true, 0L, Long.MIN_VALUE);
            } else if (ACTION_BATTERY_HEAT_AUTO_CHANGED.equals(a)) {
                if (!intent.hasExtra(EXTRA_BATTERY_HEAT_AUTO_ENABLED)) return;
                applyAutoSettingChange(intent.getBooleanExtra(
                        EXTRA_BATTERY_HEAT_AUTO_ENABLED, false));
            } else if (ACTION_REQUEST_BATTERY_HEAT.equals(a)) {
                // ACTION_REQUEST_BATTERY_HEAT only asks for the current cached snapshot. The
                // auto setting is used for decisions, not as confirmation before a UI response.
                requestBroadcastUpdate();
            }
        }
    };

    private void applyAutoSettingChange(boolean enabled) {
        ++autoSettingRevision;
        autoSettingKnown = true;
        cachedAutoEnabled = enabled;
        advanceAutoDecision();
        requestBroadcastUpdate();
        maybeAutoActivate("setting-change");
    }

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
        String action = intent == null ? ACTION_STARTUP_SETTINGS_REFRESH : intent.getAction();
        if (ACTION_PHYSICAL_WAKE_SETTINGS_REFRESH.equals(action)) {
            // If this is the start which creates the service during boot/wake, a later startup
            // start must not enqueue a second provider query for the same physical boundary.
            startupRefreshRequested = true;
            postSettingsRefresh("physical-wake");
        } else if (ACTION_STARTUP_SETTINGS_REFRESH.equals(action) || action == null) {
            if (!startupRefreshRequested) {
                startupRefreshRequested = true;
                postSettingsRefresh("startup");
            }
        } else {
            Log.w(TAG, "Ignoring unknown start action: " + action);
        }
        return START_STICKY;
    }

    private void postSettingsRefresh(String reason) {
        Handler worker = handler;
        if (destroyed || worker == null) return;
        worker.post(() -> requestSettingsRefresh(reason, true));
    }

    static void requestStartup(Context context) {
        requestSettingsRefreshStart(context, ACTION_STARTUP_SETTINGS_REFRESH);
    }

    static void requestPhysicalWake(Context context) {
        requestSettingsRefreshStart(context, ACTION_PHYSICAL_WAKE_SETTINGS_REFRESH);
    }

    private static void requestSettingsRefreshStart(Context context, String action) {
        Intent intent = new Intent(context, BatteryHeatService.class).setAction(action);
        context.startForegroundService(intent);
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
            worker.removeCallbacks(batterySafetyWatchdog);
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
        filter.addAction(ACTION_BATTERY_HEAT_AUTO_CHANGED);
        try {
            ContextCompat.registerReceiver(this, uiReceiver, filter, BIND_PERMISSION, handler,
                    ContextCompat.RECEIVER_EXPORTED);
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
        requestBroadcastUpdate();
        handler.postDelayed(batterySafetyWatchdog, BATTERY_SAFETY_WATCHDOG_MS);
    }

    private final Runnable batterySafetyWatchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                long now = SystemClock.elapsedRealtime();
                if (isVehicleSnapshotIncomplete()
                        && now - lastVehicleSnapshotRequestElapsed
                        >= INCOMPLETE_SNAPSHOT_RETRY_MS) {
                    requestVehicleStateSnapshot();
                }
                // Uses only cached, generation-fenced state. Settings/provider reads remain tied
                // to startup, wake, and the explicit setting-change event.
                maybeAutoActivate("safety-watchdog");
            } finally {
                if (!destroyed) {
                    handler.postDelayed(this, BATTERY_SAFETY_WATCHDOG_MS);
                }
            }
        }
    };

    private void resetVehicleSnapshotTracking() {
        vehicleFieldsSeenMask = 0;
    }

    private boolean isVehicleSnapshotIncomplete() {
        return vehicleFieldsSeenMask != ALL_VEHICLE_FIELDS_MASK;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Прогрев батареи", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
