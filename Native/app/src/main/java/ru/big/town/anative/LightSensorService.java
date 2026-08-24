package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сервис автоматического управления фарами по датчику освещённости.
 *
 * Архитектура (CAN callbacks + internal safety watchdog), проверена декомпиляцией
 * CarSignalService:
 *  - Подписка: регистрируем колбэк через TX=46 (writeStrongBinder). Сервис ONEWAY-ом
 *    вызывает onLightSensorChanged(level) — код 13, дескриптор
 *    "com.qinggan.carsignal.ICarSignalServiceCallBack". Это мгновенный push при
 *    изменении освещённости (уровень 0–7).
 *  - Начальный снимок: колбэки дельта-only (не отдают текущее значение), поэтому
 *    при коннекте читаем уровень через TX=36 (getLightSensorLevel) на отдельной
 *    single-flight очереди, не блокируя main и bind/reconnect.
 *  - Входящие CAN-события coalesce/filter до одной последовательной обработки и не создают
 *    неограниченные retry/poll цепочки. Отдельно от них раз в {@link #SAFETY_POLL_MS} работает
 *    один внутренний watchdog: обновляет TX36 и повторно выставляет вычисленный таргет. Это
 *    страховка, если BCM/пользователь вернул свет в Auto, а соответствующий delta-callback
 *    потерялся. Частота watchdog не зависит от частоты входящих CAN-событий.
 *  - Общая process-wide подписка CanBusEventHub фильтрует LightStatus, Gear и только
 *    VehicleState 1072 до очереди этого сервиса. Когда BCM сам уходит в «авто» (перевод КПП
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
 *  - level > threshOff → наружный свет выключен (setHeadlights(false))
 *  - между порогами    → не менять
 *
 * CAN отправляется при изменении целевого режима и редким safety-watchdog для возврата к
 * вычисленному таргету. Подтверждённый ручной OFF и незавершённая ручная команда остаются
 * защитными границами и watchdog их не перезаписывает.
 * Ограничение железа: при подрулевом в AUTO VehicleCanBusTool может отменять
 * команду ближнего света.
 */
public class LightSensorService extends Service {

    private static final String TAG = "$$$ LightSensorService $$$";
    private static final String CHANNEL_ID = "light_sensor_channel";

    // Broadcast для передачи уровня датчика в RestoreMode UI
    public static final String ACTION_LUX_UPDATE  = "ru.big.town.anative.LUX_UPDATE";
    private static final String ACTION_REQUEST_LUX_UPDATE =
            "ru.big.town.anative.REQUEST_LUX_UPDATE";
    public static final String EXTRA_SENSOR_LEVEL = "sensorLevel"; // int, -1 если датчик недоступен
    static final String ACTION_PHYSICAL_WAKE =
            "ru.big.town.anative.action.LIGHT_SENSOR_PHYSICAL_WAKE";
    static final String ACTION_RESUME_AUTO =
            "ru.big.town.anative.action.LIGHT_SENSOR_RESUME_AUTO";
    private static final String EXTRA_PHYSICAL_WAKE_GENERATION =
            "ru.big.town.anative.extra.LIGHT_SENSOR_PHYSICAL_WAKE_GENERATION";
    private static final String EXTRA_RESUME_AUTO_GENERATION =
            "ru.big.town.anative.extra.LIGHT_SENSOR_RESUME_AUTO_GENERATION";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    // Конечная задержка между попытками после конкретного сбоя. Таймер сам не открывает
    // новый бюджет: после исчерпания ждём reconnect/physical wake/новый сигнал.
    private static final long BIND_RETRY_MS  = 5_000L;
    private static final int MAX_BIND_RETRIES_PER_EVENT = 2;
    private static final int MAX_REGISTRATION_RETRIES_PER_EPOCH = 1;
    private static final int MAX_SENSOR_QUERY_RETRIES_PER_REQUEST = 1;
    private static final int MAX_COMMIT_RETRIES_PER_DECISION = 1;
    // Это намеренный внутренний poll, а не реакция на CAN callback. Он запускается ровно один
    // раз на сервис и сам переармляется с фиксированной частотой независимо от CAN-трафика.
    private static final long SAFETY_POLL_MS = 30_000L;
    private static final long SAFETY_POLL_INITIAL_DELAY_MS = 2_000L;
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
    private static final int    MAX_OUTSTANDING_CALLBACKS = 2;
    private static final AtomicInteger OUTSTANDING_CALLBACKS = new AtomicInteger();

    // Best-effort unregister is process-scoped and bounded by the two registration slots: a stuck
    // vendor TX47 can retain one running call plus at most one other callback, never a queue per
    // reconnect/service start.
    private static final ThreadPoolExecutor CAR_SIGNAL_QUERY_EXECUTOR =
            newBoundedBinderExecutor("CarSignalQuery");
    private static final ThreadPoolExecutor CAR_SIGNAL_REGISTRATION_EXECUTOR =
            newBoundedBinderExecutor("CarSignalRegistration");
    private static final ThreadPoolExecutor CAR_SIGNAL_CLEANUP_EXECUTOR =
            newBoundedBinderExecutor("CarSignalCleanup");
    private static final ThreadPoolExecutor LIGHT_SETTINGS_EXECUTOR =
            newBoundedBinderExecutor("LightSettings");
    private static final Object CALLBACK_CLEANUP_LOCK = new Object();
    private static final ArrayDeque<CallbackCleanupRequest> PENDING_CALLBACK_CLEANUPS =
            new ArrayDeque<>(MAX_OUTSTANDING_CALLBACKS);
    private static CallbackCleanupRequest runningCallbackCleanup;
    private static boolean callbackCleanupWaitingForEvent;
    private static boolean callbackCleanupRetryRequested;
    private static WeakReference<LightSensorService> activeCleanupOwner =
            new WeakReference<>(null);

    private static ThreadPoolExecutor newBoundedBinderExecutor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final String CAR_SIGNAL_ACTION  = "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_SIGNAL_PACKAGE = "com.qinggan.carsignal.service";

    // CanBus signals routed through the single process-wide callback.
    private static final int    GEAR_DRIVE               = 3;
    // BCM_RSM_lightSWReason (value 1072): 0 Day, 1 Others, 2 Dark, 3 Tunnel, 4 Darkstart
    private static final int    RSM_LIGHT_SW_REASON      = 1072;
    // Задержка после перевода в Drive: даём BCM сбросить фары в Auto, затем выставляем наш таргет.
    private static final long   DRIVE_FALLBACK_MS        = 5_000L;
    // Окно после нашей CAN-команды, в течение которого статус фар считаем «эхом»
    // своей же команды и игнорируем (защита от самозацикливания).
    private static final long HEADLIGHT_GUARD_MS = 2_500L;
    // Выдержка после того как поймали «авто», прежде чем вернуть таргет. Если за это
    // время состояние ушло из «авто» — переустановку отменяем (debounce + анти-луп).
    private static final long CANBUS_REASSERT_DELAY_MS = 5_000L;

    // OEM Auto, выбранный пользователем отдельным действием руля, нельзя принимать за самовольный
    // BCM-сброс. Флаг живёт в процессе и снимается следующим решением датчика или ручной командой
    // OFF/LOW; хранить его между перезапусками не нужно — force-init снова применит датчик.
    private static final ManualAutoGate MANUAL_AUTO_GATE = new ManualAutoGate();
    private static volatile long lastManualIntentFenceElapsedRealtime;
    private static final Object AUTO_RESUME_ORDER_LOCK = new Object();
    private static final AtomicLong NEXT_AUTO_RESUME_GENERATION = new AtomicLong();
    private static final AtomicLong PENDING_AUTO_RESUME_GENERATION = new AtomicLong();
    private static volatile long pendingAutoResumeIngressElapsedRealtime;
    private static final Object PHYSICAL_WAKE_ORDER_LOCK = new Object();
    private static final AtomicLong NEXT_PHYSICAL_WAKE_REQUEST = new AtomicLong();
    private static long pendingPhysicalWakeRequest;
    private static long pendingPhysicalWakeIngressElapsedRealtime;
    private static long pendingPhysicalWakeApplyGeneration;

    private Handler timerHandler;
    private volatile LatestIntDelivery sensorCallbackDelivery;
    private HandlerThread carSignalIoThread;
    private Handler carSignalIoHandler;
    private Executor carSignalIoExecutor;
    private final AtomicBoolean carSignalMaintenancePosted = new AtomicBoolean();
    private final AtomicBoolean carSignalRecoveryRearmRequested = new AtomicBoolean();
    private boolean sensorQueryRunning;
    private boolean sensorQueryRequested;
    private long nextSensorApplyGeneration;
    private SensorApplyRequest pendingIoSensorApply;
    private SensorApplyRequest pendingIoSettingsRequest;
    private long pendingIoSettingsGeneration;
    private SensorQueryRun runningSensorQuery;
    private SensorApplyRequest scheduledSensorQueryRetry;
    private final Runnable sensorQueryRetryRunnable = this::runSensorQueryRetryOnIo;
    private IBinder carSignalBinder = null;
    private CarSignalCallbackBinder carSignalCallbackBinder;
    private boolean carSignalBindingRequested = false;
    private boolean carSignalConnected = false;
    private boolean callbackRegistered = false;
    private boolean callbackRegistrationInFlight = false;
    private RegisterRequest pendingRegistration;
    private RegisterRequest runningRegistration;
    private long    lastBindAttempt = -BIND_RETRY_MS;
    private final Runnable carSignalRebindRunnable = this::runCarSignalRebindOnIo;
    private final EventRetryBudget carSignalBindRetryBudget =
            new EventRetryBudget(MAX_BIND_RETRIES_PER_EVENT);
    private long carSignalRecoveryScope;
    private long scheduledCarSignalRecoveryScope;
    private final EventRetryBudget callbackRegistrationRetryBudget =
            new EventRetryBudget(MAX_REGISTRATION_RETRIES_PER_EPOCH);
    private long scheduledRegistrationRetryEpoch;
    private final Runnable callbackRegistrationRetryRunnable =
            this::runCallbackRegistrationRetryOnIo;
    private long carSignalEpoch = 0L;
    private volatile long activeCarSignalEpoch = 0L;
    private volatile CarSignalCallbackBinder activeCarSignalCallback;
    private ServiceConnection carSignalConnection;
    private long nextCarSignalBindingGeneration;
    private long activeCarSignalBindingGeneration;

    // Текущая зафиксированная цель: true = ближний свет, false = наружный свет выключен
    private boolean headlightsOn = false;
    private volatile boolean everSent = false;
    private boolean forceInitCompleted = false;
    private long    readyCarSignalEpoch = 0L;
    private long    forceInitCarSignalEpoch = 0L;
    private long    forceInitManualRevision;
    private long    forceInitDecisionStartedElapsed;
    private long    driveFallbackManualRevision;
    private long    driveFallbackDecisionStartedElapsed;
    private long    canbusReassertManualRevision;
    private long    canbusReassertDecisionStartedElapsed;
    private long    nextCommitSequence = 0L;
    private volatile long activeCommitSequence = 0L;
    private long    pendingSensorEpoch = 0L;
    private long    pendingSensorRevision = 0L;
    private int     pendingSensorLevel = -1;
    private long    lastCommitElapsed  = 0L;
    private volatile long lastFrameAttemptElapsed;
    private final AtomicLong nextFrameAttemptIdentity = new AtomicLong();
    private volatile long lastFrameAttemptIdentity;
    private volatile long lastFrameAttemptRequestSequence;
    private CommitRequest lastSuccessfulCommit;
    private CommitRequest runningCommit;
    private CommitRequest pendingCommit;
    private CommitRequest retryCommit;
    private PendingExternalOff pendingExternalOff;
    private boolean automaticDecisionDeferredByExternalOff;
    private final Runnable externalOffAdjudicationRunnable =
            this::adjudicatePendingExternalOff;
    private volatile long lightDecisionRevision;
    private static volatile long automaticOwnershipToken =
            ManualAutoGate.INVALID_AUTOMATIC_TOKEN;
    private final HeadlightCanTransport.ReadyListener headlightReadyListener = () -> {
        Handler main = timerHandler;
        if (main != null) main.post(this::retryCommitWhenTransportReady);
    };

    // Последний уровень датчика для broadcast в UI
    private long lastSensorEpoch = 0L;
    private long lastSensorRevision = 0L;
    private int lastSensorLevel = -1;
    private volatile SensorApplyRequest pendingMainSensorApply;
    private final LatestRequestGate<SensorApplyRequest> settingsRequestGate =
            new LatestRequestGate<>();
    private SettingsSnapshot pendingSettingsSnapshot;
    private long nextSettingsSnapshotGeneration;

    // Тестовый режим уличного сенсора: последняя КПП и последнее решение RSM (для анти-Auto по Drive)
    private volatile int lastGear = -1;
    private volatile long driveDecisionRevision;
    private volatile int lastReason = -1;
    private volatile long activeCanBusEpoch;
    private boolean physicalWakeReconcilePending;
    private long pendingPhysicalReconcileDecisionStartedElapsed;
    private boolean reassertKnownTargetPending;
    private long physicalWakeProtectionGeneration = -1L;
    private long physicalWakeProtectionStartedElapsed;
    private static volatile boolean externalOffOverrideActive;
    private volatile long externalOffOverrideRevision;
    // Event timestamps and frame-attempt timestamps use the same elapsedRealtime clock.  A CAN
    // status produced before a newer automatic decision must not cancel that decision merely
    // because the main mailbox delivered the status later.
    private long lastAutomaticDecisionStartedElapsed;

    private CanBusEventHub.Subscription canBusSubscription;
    private volatile boolean destroyed = false;
    // Последние значимые поля LightStatus — фильтр шума от поворотников/стопа
    private int lastAutoLamp   = -1;
    private int lastDippedBeam = -1;
    private int lastHeadLight  = -1;
    private long lastLightStatusEvaluatedFrameIdentity;

    // -------------------------------------------------------------------------
    // ICarSignalServiceCallBack — Binder stub (сервис вызывает onTransact ONEWAY)
    // -------------------------------------------------------------------------

    private final class CarSignalCallbackBinder extends Binder {
        final long epoch;
        final IBinder remote;
        final AtomicLong ingressRevision = new AtomicLong();
        final AtomicBoolean registrationSlotHeld = new AtomicBoolean();
        final AtomicBoolean cleanupScheduled = new AtomicBoolean();

        CarSignalCallbackBinder(long epoch, IBinder remote) {
            this.epoch = epoch;
            this.remote = remote;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (destroyed && code >= IBinder.FIRST_CALL_TRANSACTION
                    && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            if (code == CB_onLightSensorChanged) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                final int level = data.readInt();
                if (destroyed || activeCarSignalEpoch != epoch
                        || activeCarSignalCallback != this) {
                    return true;
                }
                final long revision = ingressRevision.incrementAndGet();
                LatestIntDelivery delivery = sensorCallbackDelivery;
                if (delivery != null) delivery.offer(epoch, revision, level);
                return true;
            }
            // Прочие oneway-колбэки CarSignal (код 7/25/…) тихо поглощаем — иначе Binder
            // спамит UNKNOWN_TRANSACTION на каждый. Спец-коды (INTERFACE/DUMP) — в super.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private void onCanBusEvent(CanBusEvent event) {
        if (destroyed) return;
        consumePhysicalWakeRequest(0L);
        switch (event.kind) {
            case CONNECTION:
                onCanBusConnection(event);
                break;
            case LIGHT_STATUS:
                if (event.connectionEpoch == activeCanBusEpoch) {
                    onLightStatusChanged(event);
                }
                break;
            case GEAR:
                if (event.connectionEpoch == activeCanBusEpoch) onGear(event);
                break;
            case VEHICLE_STATE:
                if (event.connectionEpoch == activeCanBusEpoch
                        && event.first == RSM_LIGHT_SW_REASON) {
                    onLightSwReason(event);
                }
                break;
            default:
                break;
        }
    }

    private void onCanBusConnection(CanBusEvent event) {
        long epoch = event.connectionEpoch;
        if (epoch <= 0L || epoch == activeCanBusEpoch) return;
        lastAutomaticDecisionStartedElapsed = event.elapsedRealtime;
        activeCanBusEpoch = epoch;
        lastGear = -1;
        lastReason = -1;
        lastAutoLamp = -1;
        lastDippedBeam = -1;
        lastHeadLight = -1;
        lastLightStatusEvaluatedFrameIdentity = 0L;
        lastCommitElapsed = 0L;
        lastFrameAttemptElapsed = 0L;
        lastFrameAttemptIdentity = 0L;
        lastFrameAttemptRequestSequence = 0L;
        lastSuccessfulCommit = null;
        automaticDecisionDeferredByExternalOff = false;
        clearPendingExternalOff();
        timerHandler.removeCallbacks(driveFallbackRunnable);
        timerHandler.removeCallbacks(canbusReassertRunnable);
        invalidateAutomaticCommits("CanBus connection epoch " + epoch);
        if (everSent && isAutomaticControlOwned()) {
            reassertKnownTargetPending = true;
            everSent = false;
        }
        physicalWakeReconcilePending = true;
        requestCarSignalMaintenance(true);
        HeadlightCanTransport.requestRecovery(this);
        long protectionStarted = currentPhysicalWakeProtectionStart();
        reconcileAfterPhysicalEvent("CanBus connection", protectionStarted,
                protectionStarted > 0L ? protectionStarted : event.elapsedRealtime);
    }

    private void acceptSensorCallbackLevel(long epoch, long revision, int level) {
        CarSignalCallbackBinder callback = activeCarSignalCallback;
        if (destroyed || readyCarSignalEpoch != epoch || activeCarSignalEpoch != epoch
                || callback == null || callback.epoch != epoch
                || callback.ingressRevision.get() != revision) {
            return;
        }
        pendingSensorEpoch = epoch;
        pendingSensorRevision = revision;
        pendingSensorLevel = level;
        timerHandler.removeCallbacks(sensorDebounceRunnable);
        timerHandler.postDelayed(sensorDebounceRunnable, SENSOR_DEBOUNCE_MS);
    }

    private void markCarSignalReadyOnMain(long epoch) {
        if (destroyed || activeCarSignalEpoch != epoch) return;
        readyCarSignalEpoch = epoch;
        lastSensorEpoch = 0L;
        lastSensorRevision = 0L;
        lastSensorLevel = -1;
        pendingSensorEpoch = 0L;
        pendingSensorRevision = 0L;
        pendingSensorLevel = -1;
        timerHandler.removeCallbacks(sensorDebounceRunnable);
        timerHandler.removeCallbacks(forceInitRunnable);
        if (physicalWakeReconcilePending) {
            reconcileAfterPhysicalEvent("CarSignal ready");
        }
        SensorApplyRequest pending = pendingMainSensorApply;
        boolean forceAlreadyPending = pending != null && pending.epoch == epoch
                && pending.mode == SensorApplyMode.FORCE;
        if (pending == null) {
            Handler io = carSignalIoHandler;
            if (io != null) io.post(() -> {
                if (!destroyed && carSignalConnected && carSignalEpoch == epoch) {
                    requestSensorLevelOnIo(null);
                }
            });
        }
        if (!forceInitCompleted && !forceAlreadyPending) {
            forceInitCarSignalEpoch = epoch;
            forceInitManualRevision = MANUAL_AUTO_GATE.currentRevision();
            forceInitDecisionStartedElapsed = SystemClock.elapsedRealtime();
            timerHandler.postDelayed(forceInitRunnable, FORCE_INIT_MS);
        }
    }

    private void invalidateCarSignalOnMain(long closingEpoch) {
        if (readyCarSignalEpoch != closingEpoch) return;
        // CarSignal supplies only the fallback sensor sample. A target already formed from RSM or
        // a fully fenced sample remains valid and uses the independent Headlight/CanBus transport.
        physicalWakeReconcilePending = true;
        readyCarSignalEpoch = 0L;
        forceInitCarSignalEpoch = 0L;
        lastSensorEpoch = 0L;
        lastSensorRevision = 0L;
        lastSensorLevel = -1;
        pendingSensorEpoch = 0L;
        pendingSensorRevision = 0L;
        pendingSensorLevel = -1;
        if (pendingMainSensorApply != null && pendingMainSensorApply.epoch == closingEpoch) {
            pendingMainSensorApply = null;
        }
        if (pendingSettingsSnapshot != null
                && pendingSettingsSnapshot.request.epoch == closingEpoch) {
            pendingSettingsSnapshot = null;
        }
        timerHandler.removeCallbacks(forceInitRunnable);
        timerHandler.removeCallbacks(sensorDebounceRunnable);
    }

    private void requestSensorLevelForApply(long epoch, SensorApplyMode mode, String reason,
                                            boolean cancelOnManualAuto) {
        requestSensorLevelForApply(epoch, mode, reason, cancelOnManualAuto, false, 0L,
                SystemClock.elapsedRealtime());
    }

    private void requestSensorLevelForApply(long epoch, SensorApplyMode mode, String reason,
                                            boolean cancelOnManualAuto, boolean requiresDrive,
                                            long driveRevision) {
        requestSensorLevelForApply(epoch, mode, reason, cancelOnManualAuto, requiresDrive,
                driveRevision, SystemClock.elapsedRealtime());
    }

    private void requestSensorLevelForApply(long epoch, SensorApplyMode mode, String reason,
                                            boolean cancelOnManualAuto, boolean requiresDrive,
                                            long driveRevision, long decisionStartedElapsed) {
        if (destroyed || externalOffOverrideActive || hasManualOwnershipFence()
                || readyCarSignalEpoch != epoch
                || activeCarSignalEpoch != epoch) {
            return;
        }
        SensorApplyRequest existing = pendingMainSensorApply;
        long manualRevision = MANUAL_AUTO_GATE.currentRevision();
        final SensorApplyRequest request;
        if (existing != null && existing.epoch == epoch
                && existing.mode.priority >= mode.priority
                && existing.cancelOnManualAuto == cancelOnManualAuto
                && existing.manualRevision == manualRevision
                && existing.requiresDrive == requiresDrive
                && existing.decisionStartedElapsed == decisionStartedElapsed
                && (!requiresDrive || existing.driveRevision == driveRevision)) {
            request = existing;
            request.sensorQueryRetriesUsed.set(0);
        } else {
            lastAutomaticDecisionStartedElapsed = decisionStartedElapsed;
            request = new SensorApplyRequest(epoch, ++nextSensorApplyGeneration, mode, reason,
                    cancelOnManualAuto, manualRevision, requiresDrive, driveRevision,
                    decisionStartedElapsed);
            pendingMainSensorApply = request;
        }
        LightSettingsPolicy.Decision decision = settingsDecision(request);
        if (decision == LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
            SettingsSnapshot snapshot = pendingSettingsSnapshot;
            if (snapshot != null && snapshot.request == request) {
                requestFreshSensorForSnapshot(request, snapshot.sensorFence.settingsGeneration);
                return;
            }
            // Read thresholds first, then take exactly one fresh TX36 sample. Starting with TX36
            // would require a second confirmation read after the blocking provider query.
            requestSettingsForApply(request);
            return;
        }
        if (applySensorRequest(request, -1, 0L, 0L)) {
            completeSensorApplyOnMain(request);
        }
    }

    private void completeSensorApplyOnMain(SensorApplyRequest request) {
        if (pendingMainSensorApply != request) return;
        pendingMainSensorApply = null;
        if (pendingSettingsSnapshot != null && pendingSettingsSnapshot.request == request) {
            pendingSettingsSnapshot = null;
        }
        acknowledgeSensorApplyFromCallback(request.epoch, request.generation);
    }

    private void requestFreshSensorForSnapshot(SensorApplyRequest request,
                                               long settingsGeneration) {
        Handler io = carSignalIoHandler;
        if (io == null) return;
        io.post(() -> {
            if (destroyed || request.epoch != carSignalEpoch
                    || pendingMainSensorApply != request) {
                return;
            }
            pendingIoSettingsRequest = request;
            pendingIoSettingsGeneration = settingsGeneration;
            requestSensorLevelOnIo(request);
        });
    }

    private enum SensorApplyMode {
        IF_UNSENT(1), FORCE(2);

        final int priority;

        SensorApplyMode(int priority) {
            this.priority = priority;
        }
    }

    private static final class SensorApplyRequest {
        final long epoch;
        final long generation;
        final SensorApplyMode mode;
        final String reason;
        final boolean cancelOnManualAuto;
        final long manualRevision;
        final boolean requiresDrive;
        final long driveRevision;
        final long decisionStartedElapsed;
        final AtomicInteger sensorQueryRetriesUsed = new AtomicInteger();

        SensorApplyRequest(long epoch, long generation, SensorApplyMode mode, String reason,
                           boolean cancelOnManualAuto, long manualRevision, boolean requiresDrive,
                           long driveRevision, long decisionStartedElapsed) {
            this.epoch = epoch;
            this.generation = generation;
            this.mode = mode;
            this.reason = reason;
            this.cancelOnManualAuto = cancelOnManualAuto;
            this.manualRevision = manualRevision;
            this.requiresDrive = requiresDrive;
            this.driveRevision = driveRevision;
            this.decisionStartedElapsed = decisionStartedElapsed;
        }
    }

    private static final class CommitRequest {
        final long sequence;
        final long decisionRevision;
        final long automaticToken;
        final long physicalWakeGeneration;
        final long canBusEpoch;
        volatile boolean requiresDrive;
        volatile long driveRevision;
        final boolean targetOn;
        final String reason;
        final long decisionStartedElapsed;
        volatile boolean protectPreFrameOff;
        volatile long preFrameOffProtectionStartedElapsed;
        volatile long preFrameOffProtectionEndedElapsed;
        volatile long frameAttemptedElapsed;
        volatile long frameAttemptIdentity;
        volatile long terminalFrameAttemptIdentity;
        volatile ApplyEngine.WakeActionResult terminalFrameResult;
        int retriesUsed;

        CommitRequest(long sequence, long decisionRevision, long automaticToken,
                      long physicalWakeGeneration, long canBusEpoch, boolean requiresDrive,
                      long driveRevision,
                      boolean targetOn, String reason, long decisionStartedElapsed,
                      long preFrameOffProtectionStartedElapsed) {
            this.sequence = sequence;
            this.decisionRevision = decisionRevision;
            this.automaticToken = automaticToken;
            this.physicalWakeGeneration = physicalWakeGeneration;
            this.canBusEpoch = canBusEpoch;
            this.requiresDrive = requiresDrive;
            this.driveRevision = driveRevision;
            this.targetOn = targetOn;
            this.reason = reason;
            this.decisionStartedElapsed = decisionStartedElapsed;
            this.protectPreFrameOff = preFrameOffProtectionStartedElapsed > 0L;
            this.preFrameOffProtectionStartedElapsed = preFrameOffProtectionStartedElapsed;
        }

        boolean protectsOffEventBeforeFrame(long eventElapsedRealtime) {
            long started = preFrameOffProtectionStartedElapsed;
            long ended = preFrameOffProtectionEndedElapsed;
            return started > 0L && eventElapsedRealtime >= started
                    && (ended == 0L || eventElapsedRealtime <= ended);
        }
    }

    private static final class PendingExternalOff {
        final long eventSequence;
        final long eventElapsedRealtime;
        final int autoLamp;
        final int dippedBeam;
        final int headLight;
        final long wakeGeneration;
        final long decisionRevision;
        final long automaticToken;
        final long requestSequence;
        final long frameAttemptIdentity;
        final long adjudicationDueElapsedRealtime;
        boolean terminalKnown;

        PendingExternalOff(CanBusEvent event, long wakeGeneration,
                           long decisionRevision, long automaticToken,
                           long requestSequence, long frameAttemptIdentity,
                           long adjudicationDueElapsedRealtime,
                           boolean terminalKnown) {
            this.eventSequence = event.sequence;
            this.eventElapsedRealtime = event.elapsedRealtime;
            this.autoLamp = event.first;
            this.dippedBeam = event.second;
            this.headLight = event.third;
            this.wakeGeneration = wakeGeneration;
            this.decisionRevision = decisionRevision;
            this.automaticToken = automaticToken;
            this.requestSequence = requestSequence;
            this.frameAttemptIdentity = frameAttemptIdentity;
            this.adjudicationDueElapsedRealtime = adjudicationDueElapsedRealtime;
            this.terminalKnown = terminalKnown;
        }

        boolean samePayload(CanBusEvent event) {
            return autoLamp == event.first && dippedBeam == event.second
                    && headLight == event.third;
        }
    }

    private static final class SettingsSnapshot {
        final SensorApplyRequest request;
        final LightThresholds thresholds;
        final SensorSampleFence sensorFence;

        SettingsSnapshot(SensorApplyRequest request, LightThresholds thresholds,
                         long generation, long liveRevisionFence) {
            this.request = request;
            this.thresholds = thresholds;
            this.sensorFence = new SensorSampleFence(generation, liveRevisionFence);
        }
    }

    private static final class SensorQueryRun {
        final IBinder binder;
        final CarSignalCallbackBinder callback;
        final long epoch;
        final long ingressRevision;
        final SensorApplyRequest apply;
        final long settingsGeneration;

        SensorQueryRun(IBinder binder, CarSignalCallbackBinder callback, long epoch,
                       long ingressRevision, SensorApplyRequest apply,
                       long settingsGeneration) {
            this.binder = binder;
            this.callback = callback;
            this.epoch = epoch;
            this.ingressRevision = ingressRevision;
            this.apply = apply;
            this.settingsGeneration = settingsGeneration;
        }
    }

    // -------------------------------------------------------------------------
    // CarSignal Binder IO. All fields in this section are confined to carSignalIoHandler.
    // -------------------------------------------------------------------------

    private final class CarSignalConnection implements ServiceConnection {
        private final long generation;

        CarSignalConnection(long generation) {
            this.generation = generation;
        }

        private boolean isCurrent() {
            return carSignalConnection == this
                    && activeCarSignalBindingGeneration == generation;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!isCurrent() || destroyed) return;
            carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
            carSignalBindingRequested = true;
            carSignalBinder = service;
            carSignalConnected = true;
            callbackRegistered = false;
            callbackRegistrationInFlight = false;
            long epoch = ++carSignalEpoch;
            callbackRegistrationRetryBudget.reset(epoch);
            scheduledRegistrationRetryEpoch = 0L;
            retryPendingCallbackCleanups();
            carSignalCallbackBinder = new CarSignalCallbackBinder(epoch, service);
            activeCarSignalCallback = carSignalCallbackBinder;
            activeCarSignalEpoch = epoch;
            Log.i(TAG, "CarSignalService connected, alive=" + service.isBinderAlive());
            startRegisterCallbackOnIo();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!isCurrent()) return;
            OldCarSignalSession old = invalidateCarSignalRemoteOnIo();
            if (old.registered) scheduleUnregister(old.remote, old.callback);
            openCarSignalRecoveryScopeOnIo();
            lastBindAttempt = SystemClock.elapsedRealtime();
            scheduleCarSignalRebindOnIo();
            Log.w(TAG, "CarSignalService disconnected — finite reconnect watchdog armed");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (isCurrent()) restartCarSignalBindingOnIo("binding died", true);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (isCurrent()) restartCarSignalBindingOnIo("null binding", false);
        }
    }

    private static final class OldCarSignalSession {
        final IBinder remote;
        final CarSignalCallbackBinder callback;
        final boolean registered;
        final long epoch;

        OldCarSignalSession(IBinder remote, CarSignalCallbackBinder callback,
                            boolean registered, long epoch) {
            this.remote = remote;
            this.callback = callback;
            this.registered = registered;
            this.epoch = epoch;
        }
    }

    private static final class RegisterRequest {
        final IBinder remote;
        final CarSignalCallbackBinder callback;
        final long epoch;

        RegisterRequest(IBinder remote, CarSignalCallbackBinder callback, long epoch) {
            this.remote = remote;
            this.callback = callback;
            this.epoch = epoch;
        }
    }

    private static final class CallbackCleanupRequest {
        final IBinder remote;
        final CarSignalCallbackBinder callback;

        CallbackCleanupRequest(IBinder remote, CarSignalCallbackBinder callback) {
            this.remote = remote;
            this.callback = callback;
        }
    }

    private void runCarSignalRebindOnIo() {
        long scope = scheduledCarSignalRecoveryScope;
        if (!carSignalBindRetryBudget.isCurrent(scope)) return;
        if (carSignalBindingRequested && !carSignalConnected) {
            restartPendingCarSignalBindingOnIo(scope);
        } else {
            ensureBound();
        }
    }

    private void runCallbackRegistrationRetryOnIo() {
        long epoch = scheduledRegistrationRetryEpoch;
        if (!destroyed && callbackRegistrationRetryBudget.isCurrent(epoch)
                && epoch == carSignalEpoch) {
            startRegisterCallbackOnIo();
        }
    }

    private void runSensorQueryRetryOnIo() {
        SensorApplyRequest request = scheduledSensorQueryRetry;
        scheduledSensorQueryRetry = null;
        if (!destroyed && request != null && pendingIoSensorApply == request
                && request.epoch == carSignalEpoch) {
            requestSensorLevelOnIo(request);
        }
    }

    private void requestCarSignalMaintenance(boolean rearmRecovery) {
        if (rearmRecovery) {
            carSignalRecoveryRearmRequested.set(true);
            retryPendingCallbackCleanups();
        }
        if (destroyed || !carSignalMaintenancePosted.compareAndSet(false, true)) return;
        Handler io = carSignalIoHandler;
        if (io == null || !io.post(() -> {
            try {
                if (destroyed) return;
                if (carSignalRecoveryRearmRequested.getAndSet(false)) {
                    openCarSignalRecoveryScopeOnIo();
                }
                if (carSignalConnected
                        && (carSignalBinder == null || !carSignalBinder.isBinderAlive())) {
                    Log.w(TAG, "CarSignal maintenance found dead binder — replacing binding");
                    releaseCarSignalBindingOnIo("maintenance found dead binder");
                    lastBindAttempt = -BIND_RETRY_MS;
                    ensureBound();
                    return;
                }
                ensureBound();
                startRegisterCallbackOnIo();
            } finally {
                carSignalMaintenancePosted.set(false);
                if (carSignalRecoveryRearmRequested.get() && !destroyed) {
                    requestCarSignalMaintenance(false);
                }
            }
        })) {
            carSignalMaintenancePosted.set(false);
        }
    }

    private void openCarSignalRecoveryScopeOnIo() {
        long scope = ++carSignalRecoveryScope;
        carSignalBindRetryBudget.reset(scope);
        scheduledCarSignalRecoveryScope = scope;
        if (!carSignalBindingRequested) lastBindAttempt = -BIND_RETRY_MS;
        carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
        if (carSignalConnected && carSignalEpoch > 0L) {
            callbackRegistrationRetryBudget.reset(carSignalEpoch);
            carSignalIoHandler.removeCallbacks(callbackRegistrationRetryRunnable);
        }
    }

    private void ensureBound() {
        if (destroyed) return;
        if (carSignalBindingRequested) {
            if (!carSignalConnected) scheduleCarSignalRebindOnIo();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBindAttempt < BIND_RETRY_MS) return;
        lastBindAttempt = now;
        long generation = ++nextCarSignalBindingGeneration;
        CarSignalConnection connection = new CarSignalConnection(generation);
        carSignalConnection = connection;
        activeCarSignalBindingGeneration = generation;
        try {
            Intent intent = new Intent(CAR_SIGNAL_ACTION);
            intent.setPackage(CAR_SIGNAL_PACKAGE);
            boolean ok = bindService(intent, Context.BIND_AUTO_CREATE,
                    carSignalIoExecutor, connection);
            carSignalBindingRequested = ok;
            Log.i(TAG, "ensureBound: bindService returned " + ok);
            if (!ok) {
                carSignalConnection = null;
                activeCarSignalBindingGeneration = 0L;
                scheduleCarSignalRebindOnIo();
            } else {
                // bindService(true) has no guaranteed failure callback. One finite, generation-
                // fenced watchdog per recovery budget replaces a request which never connects.
                scheduleCarSignalRebindOnIo();
            }
        } catch (Exception e) {
            carSignalBindingRequested = false;
            carSignalConnection = null;
            activeCarSignalBindingGeneration = 0L;
            Log.e(TAG, "ensureBound: exception: " + e.getMessage(), e);
            scheduleCarSignalRebindOnIo();
        }
    }

    private OldCarSignalSession invalidateCarSignalRemoteOnIo() {
        long closingEpoch = carSignalEpoch;
        OldCarSignalSession old = new OldCarSignalSession(
                carSignalBinder, carSignalCallbackBinder,
                callbackRegistered, closingEpoch);
        activeCarSignalEpoch = 0L;
        activeCarSignalCallback = null;
        carSignalBinder = null;
        carSignalCallbackBinder = null;
        carSignalConnected = false;
        callbackRegistered = false;
        callbackRegistrationInFlight = false;
        if (pendingRegistration != null
                && pendingRegistration.callback == old.callback) {
            releaseRegistrationSlot(pendingRegistration.callback);
            pendingRegistration = null;
        }
        if (pendingIoSensorApply != null && pendingIoSensorApply.epoch == closingEpoch) {
            pendingIoSensorApply = null;
        }
        if (scheduledSensorQueryRetry != null
                && scheduledSensorQueryRetry.epoch == closingEpoch) {
            scheduledSensorQueryRetry = null;
            carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
        }
        if (pendingIoSettingsRequest != null
                && pendingIoSettingsRequest.epoch == closingEpoch) {
            pendingIoSettingsRequest = null;
            pendingIoSettingsGeneration = 0L;
        }
        sensorQueryRequested = false;
        carSignalEpoch++;
        timerHandler.post(() -> invalidateCarSignalOnMain(closingEpoch));
        return old;
    }

    private void restartCarSignalBindingOnIo(String reason, boolean externalEvent) {
        Log.w(TAG, "CarSignalService " + reason + " — replacing binding");
        releaseCarSignalBindingOnIo(reason);
        if (externalEvent) {
            openCarSignalRecoveryScopeOnIo();
            ensureBound();
        } else {
            scheduleCarSignalRebindOnIo();
        }
    }

    private void restartPendingCarSignalBindingOnIo(long scope) {
        if (destroyed || !carSignalBindRetryBudget.isCurrent(scope)
                || !carSignalBindingRequested || carSignalConnected) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long remaining = BIND_RETRY_MS - (now - lastBindAttempt);
        if (remaining > 0L) {
            scheduledCarSignalRecoveryScope = scope;
            carSignalIoHandler.postDelayed(carSignalRebindRunnable, remaining);
            return;
        }
        Log.w(TAG, "CarSignal bind produced no callback — replacing finite attempt");
        releaseCarSignalBindingOnIo("connect watchdog");
        lastBindAttempt = -BIND_RETRY_MS;
        ensureBound();
    }

    private void scheduleCarSignalRebindOnIo() {
        if (destroyed) return;
        long scope = carSignalRecoveryScope;
        if (!carSignalBindRetryBudget.claim(scope)) {
            Log.w(TAG, "CarSignal bind retries exhausted; waiting for reconnect/wake event");
            return;
        }
        scheduledCarSignalRecoveryScope = scope;
        carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
        long delay = Math.max(0L,
                lastBindAttempt + BIND_RETRY_MS - SystemClock.elapsedRealtime());
        carSignalIoHandler.postDelayed(carSignalRebindRunnable, delay);
    }

    private void releaseCarSignalBindingOnIo(String reason) {
        carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
        carSignalIoHandler.removeCallbacks(callbackRegistrationRetryRunnable);
        carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
        scheduledSensorQueryRetry = null;
        ServiceConnection connection = carSignalConnection;
        boolean wasBindingRequested = carSignalBindingRequested;
        OldCarSignalSession old = invalidateCarSignalRemoteOnIo();
        carSignalBindingRequested = false;
        carSignalConnection = null;
        activeCarSignalBindingGeneration = 0L;
        if (wasBindingRequested && connection != null) {
            try {
                unbindService(connection);
            } catch (Exception e) {
                Log.w(TAG, reason + ": CarSignal unbindService failed: " + e.getMessage());
            }
        }
        // Lifecycle is already invalidated/unbound; vendor cleanup can never delay it.
        if (old.registered) scheduleUnregister(old.remote, old.callback);
    }

    private void startRegisterCallbackOnIo() {
        if (destroyed || !carSignalConnected || carSignalBinder == null
                || carSignalCallbackBinder == null
                || callbackRegistered || callbackRegistrationInFlight
                || carSignalCallbackBinder.cleanupScheduled.get()) {
            return;
        }
        IBinder remote = carSignalBinder;
        CarSignalCallbackBinder callback = carSignalCallbackBinder;
        long epoch = carSignalEpoch;
        if (!reserveRegistrationSlot(callback)) {
            Log.w(TAG, "registerCallback deferred: cleanup backpressure");
            scheduleCallbackRegistrationRetryOnIo(epoch);
            return;
        }
        callbackRegistrationInFlight = true;
        pendingRegistration = new RegisterRequest(remote, callback, epoch);
        startNextRegistrationOnIo();
    }

    private void startNextRegistrationOnIo() {
        if (runningRegistration != null || pendingRegistration == null) return;
        RegisterRequest request = pendingRegistration;
        pendingRegistration = null;
        runningRegistration = request;
        try {
            CAR_SIGNAL_REGISTRATION_EXECUTOR.execute(() -> {
                RegistrationResult result = registerCallbackTransaction(
                        request.remote, request.callback);
                if (result == RegistrationResult.NOT_SENT) {
                    releaseRegistrationSlot(request.callback);
                } else if (result == RegistrationResult.AMBIGUOUS) {
                    // transact reached the vendor but the reply failed: cleanup before retrying,
                    // otherwise an uncounted observer could bypass the process-wide slot limit.
                    scheduleUnregister(request.remote, request.callback);
                }
                Handler io = carSignalIoHandler;
                boolean delivered = io != null
                        && io.post(() -> finishRegisterCallbackOnIo(request, result));
                if (!delivered && result == RegistrationResult.SUCCESS) {
                    // The lifecycle lane is already gone: compensate on this isolated worker.
                    scheduleUnregister(request.remote, request.callback);
                }
            });
        } catch (RejectedExecutionException e) {
            runningRegistration = null;
            releaseRegistrationSlot(request.callback);
            if (request.callback == carSignalCallbackBinder) {
                callbackRegistrationInFlight = false;
                scheduleCallbackRegistrationRetryOnIo(request.epoch);
            }
        }
    }

    private enum RegistrationResult { SUCCESS, NOT_SENT, AMBIGUOUS }

    private RegistrationResult registerCallbackTransaction(IBinder remote, IBinder callback) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean attempted = false;
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callback);
            attempted = true;
            if (!remote.transact(TX_registerCallback, data, reply, 0)) {
                return RegistrationResult.NOT_SENT;
            }
            reply.readException();
            Log.i(TAG, "registerCallback: OK (TX=" + TX_registerCallback + ")");
            return RegistrationResult.SUCCESS;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "registerCallback: error: " + e.getMessage());
            return attempted && remote.isBinderAlive()
                    ? RegistrationResult.AMBIGUOUS : RegistrationResult.NOT_SENT;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void finishRegisterCallbackOnIo(RegisterRequest request, RegistrationResult result) {
        if (runningRegistration != request) return;
        runningRegistration = null;
        boolean success = result == RegistrationResult.SUCCESS;
        boolean current = !destroyed && request.remote == carSignalBinder
                && request.callback == carSignalCallbackBinder
                && request.epoch == carSignalEpoch;
        if (!current) {
            if (success) scheduleUnregister(request.remote, request.callback);
        } else {
            callbackRegistrationInFlight = false;
            if (success) {
                callbackRegistered = true;
                carSignalIoHandler.removeCallbacks(callbackRegistrationRetryRunnable);
                // TX46 is delta-only. Publish readiness and take the initial TX36 snapshot only
                // after subscription is confirmed, otherwise a change between an early snapshot
                // and TX46 success remains stale until the next safety-watchdog tick.
                timerHandler.post(() -> markCarSignalReadyOnMain(request.epoch));
            } else if (result == RegistrationResult.NOT_SENT) {
                if (!request.remote.isBinderAlive()) {
                    restartCarSignalBindingOnIo("register remote died", true);
                    return;
                }
                scheduleCallbackRegistrationRetryOnIo(request.epoch);
            }
        }
        startNextRegistrationOnIo();
    }

    private void scheduleCallbackRegistrationRetryOnIo(long epoch) {
        if (destroyed || epoch != carSignalEpoch
                || !callbackRegistrationRetryBudget.claim(epoch)) {
            if (!destroyed && epoch == carSignalEpoch) {
                Log.w(TAG, "registerCallback retry exhausted; waiting for reconnect/wake event");
            }
            return;
        }
        scheduledRegistrationRetryEpoch = epoch;
        carSignalIoHandler.removeCallbacks(callbackRegistrationRetryRunnable);
        carSignalIoHandler.postDelayed(callbackRegistrationRetryRunnable, BIND_RETRY_MS);
    }

    private static boolean reserveRegistrationSlot(CarSignalCallbackBinder callback) {
        if (!callback.registrationSlotHeld.compareAndSet(false, true)) return true;
        int count = OUTSTANDING_CALLBACKS.incrementAndGet();
        if (count <= MAX_OUTSTANDING_CALLBACKS) return true;
        OUTSTANDING_CALLBACKS.decrementAndGet();
        callback.registrationSlotHeld.set(false);
        return false;
    }

    private static void releaseRegistrationSlot(CarSignalCallbackBinder callback) {
        if (callback != null && callback.registrationSlotHeld.compareAndSet(true, false)) {
            OUTSTANDING_CALLBACKS.decrementAndGet();
        }
    }

    private void scheduleUnregister(IBinder remote, CarSignalCallbackBinder callback) {
        if (remote == null || callback == null) return;
        if (!callback.registrationSlotHeld.get()) return;
        if (!callback.cleanupScheduled.compareAndSet(false, true)) return;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            boolean olderCleanupAlreadyPending = runningCallbackCleanup != null
                    || callbackCleanupWaitingForEvent
                    || !PENDING_CALLBACK_CLEANUPS.isEmpty();
            PENDING_CALLBACK_CLEANUPS.addLast(
                    new CallbackCleanupRequest(remote, callback));
            // A newly retired callback is itself a lifecycle event. One attempt may start now;
            // if an older request consumes that event and fails, let the new request receive its
            // first attempt before the lane waits again. Failures never self-reschedule.
            if (olderCleanupAlreadyPending) callbackCleanupRetryRequested = true;
            callbackCleanupWaitingForEvent = false;
        }
        startNextCallbackCleanup();
    }

    private static void retryPendingCallbackCleanups() {
        synchronized (CALLBACK_CLEANUP_LOCK) {
            if (runningCallbackCleanup != null) {
                callbackCleanupRetryRequested = true;
                return;
            }
            callbackCleanupWaitingForEvent = false;
        }
        startNextCallbackCleanup();
    }

    private static void startNextCallbackCleanup() {
        final CallbackCleanupRequest request;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            if (runningCallbackCleanup != null || callbackCleanupWaitingForEvent
                    || PENDING_CALLBACK_CLEANUPS.isEmpty()) {
                return;
            }
            request = PENDING_CALLBACK_CLEANUPS.removeFirst();
            runningCallbackCleanup = request;
        }
        try {
            CAR_SIGNAL_CLEANUP_EXECUTOR.execute(() -> finishCallbackCleanup(
                    request, unregisterCallbackTransaction(request.remote, request.callback)));
        } catch (RejectedExecutionException e) {
            synchronized (CALLBACK_CLEANUP_LOCK) {
                if (runningCallbackCleanup == request) {
                    runningCallbackCleanup = null;
                    PENDING_CALLBACK_CLEANUPS.addLast(request);
                    callbackCleanupRetryRequested = false;
                    callbackCleanupWaitingForEvent = true;
                }
            }
            Log.w(TAG, "unregisterCallback deferred until next reconnect/wake event");
        }
    }

    private static void finishCallbackCleanup(CallbackCleanupRequest request, boolean success) {
        final boolean retryAfterRunningFailure;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            if (runningCallbackCleanup != request) return;
            runningCallbackCleanup = null;
            retryAfterRunningFailure = !success && callbackCleanupRetryRequested;
            callbackCleanupRetryRequested = false;
            if (!success) {
                PENDING_CALLBACK_CLEANUPS.addLast(request);
                callbackCleanupWaitingForEvent = !retryAfterRunningFailure;
            }
        }
        if (!success) {
            if (retryAfterRunningFailure) {
                Log.w(TAG, "unregisterCallback not confirmed; retrying for queued real event");
                startNextCallbackCleanup();
            } else {
                Log.w(TAG, "unregisterCallback not confirmed; waiting for reconnect/wake event");
            }
            return;
        }
        request.callback.cleanupScheduled.set(false);
        releaseRegistrationSlot(request.callback);
        notifyCallbackCleanupComplete(request.callback);
        startNextCallbackCleanup();
    }

    private static void notifyCallbackCleanupComplete(CarSignalCallbackBinder cleaned) {
        final LightSensorService owner;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            owner = activeCleanupOwner.get();
        }
        if (owner == null || owner.destroyed) return;
        Handler io = owner.carSignalIoHandler;
        if (io != null) io.post(() -> {
            if (cleaned == owner.carSignalCallbackBinder
                    && cleaned.epoch == owner.carSignalEpoch) {
                owner.scheduleCallbackRegistrationRetryOnIo(cleaned.epoch);
            } else {
                owner.startRegisterCallbackOnIo();
            }
        });
    }

    private static boolean unregisterCallbackTransaction(IBinder remote, IBinder callback) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!remote.transact(TX_unregisterCallback, data, reply, 0)) {
                return !remote.isBinderAlive();
            }
            reply.readException();
            Log.i(TAG, "unregisterCallback: OK");
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "unregisterCallback: error: " + e.getMessage());
            return !remote.isBinderAlive();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void requestSensorLevelOnIo(SensorApplyRequest applyRequest) {
        if (destroyed || !carSignalConnected || carSignalBinder == null
                || carSignalCallbackBinder == null) {
            return;
        }
        if (applyRequest != null) {
            if (applyRequest.epoch != carSignalEpoch) return;
            SensorApplyRequest existing = pendingIoSensorApply;
            if (existing == null || existing.epoch != applyRequest.epoch
                    || existing.mode.priority < applyRequest.mode.priority
                    || (existing.mode == applyRequest.mode
                    && existing.generation < applyRequest.generation)) {
                pendingIoSensorApply = applyRequest;
            }
            if (scheduledSensorQueryRetry != null
                    && scheduledSensorQueryRetry != pendingIoSensorApply) {
                scheduledSensorQueryRetry = null;
                carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
            }
        }
        sensorQueryRequested = true;
        startSensorLevelQueryOnIo();
    }

    private void startSensorLevelQueryOnIo() {
        if (destroyed || sensorQueryRunning || !sensorQueryRequested) return;
        IBinder binder = carSignalBinder;
        CarSignalCallbackBinder callback = carSignalCallbackBinder;
        long epoch = carSignalEpoch;
        if (!carSignalConnected || binder == null || callback == null) {
            return;
        }
        sensorQueryRequested = false;
        SensorApplyRequest apply = pendingIoSensorApply;
        if (apply != null && apply.epoch != epoch) apply = null;
        if (apply != null && scheduledSensorQueryRetry == apply) {
            scheduledSensorQueryRetry = null;
            carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
        }
        long settingsGeneration = pendingIoSettingsRequest == apply
                ? pendingIoSettingsGeneration : 0L;
        long ingressRevision = callback.ingressRevision.get();
        SensorQueryRun run = new SensorQueryRun(
                binder, callback, epoch, ingressRevision, apply, settingsGeneration);
        sensorQueryRunning = true;
        runningSensorQuery = run;
        try {
            CAR_SIGNAL_QUERY_EXECUTOR.execute(() -> {
                int level = readSensorLevelOnQueryThread(binder);
                Handler io = carSignalIoHandler;
                if (io != null) {
                    io.post(() -> finishSensorLevelQueryOnIo(run, level));
                }
            });
        } catch (RejectedExecutionException e) {
            sensorQueryRunning = false;
            runningSensorQuery = null;
            sensorQueryRequested = false;
            scheduleSensorQueryRetryOnIo(run.apply);
        }
    }

    private void finishSensorLevelQueryOnIo(SensorQueryRun run, int level) {
        if (!sensorQueryRunning || runningSensorQuery != run) return;
        boolean current = !destroyed && carSignalConnected
                && run.binder == carSignalBinder && run.callback == carSignalCallbackBinder
                && run.epoch == carSignalEpoch
                && run.callback.ingressRevision.get() == run.ingressRevision;
        if (current && level >= 0
                && timerHandler.post(() -> acceptSensorQueryResultOnMain(run, level))) {
            // Keep the logical flight open until main validates the epoch/revision and acks it.
            return;
        }
        if (current && level < 0) {
            if (!run.binder.isBinderAlive()) {
                restartCarSignalBindingOnIo("TX36 binder died", true);
            } else {
                scheduleSensorQueryRetryOnIo(run.apply);
            }
        }
        sensorQueryRunning = false;
        runningSensorQuery = null;
        if (sensorQueryRequested) {
            startSensorLevelQueryOnIo();
        }
    }

    private void acceptSensorQueryResultOnMain(SensorQueryRun run, int level) {
        CarSignalCallbackBinder activeCallback = activeCarSignalCallback;
        boolean accepted = !destroyed && readyCarSignalEpoch == run.epoch
                && activeCarSignalEpoch == run.epoch
                && activeCallback == run.callback
                && run.callback.ingressRevision.get() == run.ingressRevision;
        boolean applyAccepted = false;
        if (accepted) {
            onSensorLevel(run.epoch, run.ingressRevision, level, "snapshot");
            SensorApplyRequest apply = run.apply;
            if (apply != null && pendingMainSensorApply == apply) {
                applyAccepted = applySensorRequest(
                        apply, level, run.ingressRevision, run.settingsGeneration);
                if (applyAccepted) completeSensorApplyOnMain(apply);
            }
        }
        Handler io = carSignalIoHandler;
        if (io != null) {
            final boolean acceptedAction = applyAccepted;
            if (!io.post(() -> finishSensorQueryAfterMainOnIo(run, acceptedAction))) {
                Log.w(TAG, "TX36 completion dropped: CarSignal IO stopped");
            }
        }
    }

    private void finishSensorQueryAfterMainOnIo(SensorQueryRun run, boolean applyAccepted) {
        if (!sensorQueryRunning || runningSensorQuery != run) return;
        if (applyAccepted && pendingIoSensorApply == run.apply) {
            pendingIoSensorApply = null;
            if (pendingIoSettingsRequest == run.apply) {
                pendingIoSettingsRequest = null;
                pendingIoSettingsGeneration = 0L;
            }
        }
        sensorQueryRunning = false;
        runningSensorQuery = null;
        if (sensorQueryRequested) startSensorLevelQueryOnIo();
    }

    private void acknowledgeSensorApplyFromCallback(long epoch, long generation) {
        Handler io = carSignalIoHandler;
        if (io == null) return;
        io.post(() -> {
            SensorApplyRequest pending = pendingIoSensorApply;
            if (pending != null && pending.epoch == epoch
                    && pending.generation == generation) {
                pendingIoSensorApply = null;
                if (scheduledSensorQueryRetry == pending) {
                    scheduledSensorQueryRetry = null;
                    carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
                }
                if (pendingIoSettingsRequest == pending) {
                    pendingIoSettingsRequest = null;
                    pendingIoSettingsGeneration = 0L;
                }
            }
        });
    }

    private void scheduleSensorQueryRetryOnIo(SensorApplyRequest request) {
        if (destroyed || request == null || request != pendingIoSensorApply
                || request.epoch != carSignalEpoch) {
            return;
        }
        int used;
        do {
            used = request.sensorQueryRetriesUsed.get();
            if (used >= MAX_SENSOR_QUERY_RETRIES_PER_REQUEST) return;
        } while (!request.sensorQueryRetriesUsed.compareAndSet(used, used + 1));
        scheduledSensorQueryRetry = request;
        carSignalIoHandler.removeCallbacks(sensorQueryRetryRunnable);
        carSignalIoHandler.postDelayed(sensorQueryRetryRunnable, BIND_RETRY_MS);
    }

    // -------------------------------------------------------------------------
    // RestoreMode settings IO. ContentProvider.query is synchronous Binder work, so it has its
    // own process-wide bounded lane and never runs on main or on either CarSignal lane.
    // -------------------------------------------------------------------------

    private void requestSettingsForApply(SensorApplyRequest request) {
        if (!isSettingsRequestCurrentOnMain(request)) return;
        LightSettingsPolicy.Decision decision = settingsDecision(request);
        if (decision != LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
            resolveSettingsRequestOnMain(request, decision);
            return;
        }
        SensorApplyRequest start = settingsRequestGate.offer(request);
        if (start != null) submitSettingsQuery(start);
    }

    private void submitSettingsQuery(SensorApplyRequest request) {
        if (!isSettingsRequestCurrentOnMain(request)) {
            dropSettingsQueryOnMain(request);
            return;
        }
        LightSettingsPolicy.Decision decision = settingsDecision(request);
        if (decision != LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
            finishSettingsWithoutQueryOnMain(request);
            return;
        }
        ContentResolver resolver = getApplicationContext().getContentResolver();
        WeakReference<LightSensorService> serviceRef = new WeakReference<>(this);
        try {
            LIGHT_SETTINGS_EXECUTOR.execute(() -> {
                LightSensorService beforeQuery = serviceRef.get();
                if (beforeQuery == null || beforeQuery.destroyed
                        || beforeQuery.pendingMainSensorApply != request
                        || beforeQuery.activeCarSignalEpoch != request.epoch) {
                    if (beforeQuery != null) {
                        Handler main = beforeQuery.timerHandler;
                        if (main != null) {
                            main.post(() -> beforeQuery.dropSettingsQueryOnMain(request));
                        }
                    }
                    return;
                }
                LightSettingsPolicy.Decision beforeQueryDecision =
                        beforeQuery.settingsDecision(request);
                if (beforeQueryDecision != LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
                    Handler main = beforeQuery.timerHandler;
                    if (main != null) {
                        main.post(() -> beforeQuery.finishSettingsWithoutQueryOnMain(
                                request));
                    }
                    return;
                }
                LightThresholds thresholds = queryThresholds(resolver);
                LightSensorService service = serviceRef.get();
                if (service == null) return;
                Handler main = service.timerHandler;
                if (main != null) {
                    main.post(() -> service.finishSettingsQueryOnMain(request, thresholds));
                }
            });
        } catch (RejectedExecutionException e) {
            settingsRequestGate.reject(request);
            Log.w(TAG, "threshold query queue saturated; waiting for next real light event");
        }
    }

    private boolean isSettingsRequestCurrentOnMain(SensorApplyRequest request) {
        return !destroyed && pendingMainSensorApply == request
                && request.epoch == readyCarSignalEpoch
                && request.epoch == activeCarSignalEpoch;
    }

    private LightSettingsPolicy.Decision settingsDecision(SensorApplyRequest request) {
        if (!MANUAL_AUTO_GATE.isRevisionCurrent(request.manualRevision)) {
            return LightSettingsPolicy.Decision.COMPLETE_CANCELLED;
        }
        return LightSettingsPolicy.decide(
                request.cancelOnManualAuto, MANUAL_AUTO_GATE.blocksAntiAuto(),
                request.mode == SensorApplyMode.IF_UNSENT, everSent,
                reasonToDesired(lastReason) != null);
    }

    private void finishSettingsWithoutQueryOnMain(SensorApplyRequest request) {
        LatestRequestGate.Completion<SensorApplyRequest> completion =
                settingsRequestGate.finish(request);
        if (completion.publish && isSettingsRequestCurrentOnMain(request)) {
            resolveSettingsRequestOnMain(request, settingsDecision(request));
        }
        if (completion.next != null) submitSettingsQuery(completion.next);
    }

    private void resolveSettingsRequestOnMain(
            SensorApplyRequest request, LightSettingsPolicy.Decision decision) {
        if (!isSettingsRequestCurrentOnMain(request)) return;
        if (decision == LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
            requestSettingsForApply(request);
            return;
        }
        boolean fulfilled = applySensorRequest(request, -1, 0L, 0L);
        if (fulfilled) completeSensorApplyOnMain(request);
    }

    private void dropSettingsQueryOnMain(SensorApplyRequest request) {
        LatestRequestGate.Completion<SensorApplyRequest> completion =
                settingsRequestGate.finish(request);
        if (completion.next != null) submitSettingsQuery(completion.next);
    }

    private void finishSettingsQueryOnMain(SensorApplyRequest request,
                                           LightThresholds thresholds) {
        LatestRequestGate.Completion<SensorApplyRequest> completion =
                settingsRequestGate.finish(request);
        if (completion.publish && isSettingsRequestCurrentOnMain(request)) {
            LightSettingsPolicy.Decision decision = settingsDecision(request);
            if (decision != LightSettingsPolicy.Decision.NEED_THRESHOLDS) {
                resolveSettingsRequestOnMain(request, decision);
            } else {
                CarSignalCallbackBinder callback = activeCarSignalCallback;
                long liveRevisionFence = callback != null && callback.epoch == request.epoch
                        ? callback.ingressRevision.get() : Long.MAX_VALUE;
                long settingsGeneration = ++nextSettingsSnapshotGeneration;
                pendingSettingsSnapshot = new SettingsSnapshot(
                        request, thresholds, settingsGeneration, liveRevisionFence);
                // Never apply a threshold result to the sensor value captured before the blocking
                // provider call. Request a fresh, epoch/revision-protected TX36 instead.
                requestFreshSensorForSnapshot(request, settingsGeneration);
            }
        }
        if (completion.next != null) submitSettingsQuery(completion.next);
    }

    /** Synchronous TX36 isolated from both main and the bind/register IO queue. */
    private int readSensorLevelOnQueryThread(IBinder binder) {
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            if (!binder.transact(TX_getLightSensorLevel, data, reply, 0)) return -1;
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

    static void requestPhysicalWake(Context context) {
        if (context == null) return;
        final long requestGeneration;
        synchronized (PHYSICAL_WAKE_ORDER_LOCK) {
            requestGeneration = NEXT_PHYSICAL_WAKE_REQUEST.incrementAndGet();
            pendingPhysicalWakeRequest = requestGeneration;
            pendingPhysicalWakeIngressElapsedRealtime = SystemClock.elapsedRealtime();
            pendingPhysicalWakeApplyGeneration = ApplyEngine.capturePhysicalWakeGeneration();
        }
        Intent intent = new Intent(context, LightSensorService.class);
        intent.setAction(ACTION_PHYSICAL_WAKE);
        intent.putExtra(EXTRA_PHYSICAL_WAKE_GENERATION, requestGeneration);
        context.startForegroundService(intent);
    }

    static void requestExplicitAutoResume(Context context) {
        if (context == null) return;
        final long generation;
        synchronized (AUTO_RESUME_ORDER_LOCK) {
            generation = NEXT_AUTO_RESUME_GENERATION.incrementAndGet();
            PENDING_AUTO_RESUME_GENERATION.set(generation);
            pendingAutoResumeIngressElapsedRealtime = SystemClock.elapsedRealtime();
        }
        Intent intent = new Intent(context, LightSensorService.class);
        intent.setAction(ACTION_RESUME_AUTO);
        intent.putExtra(EXTRA_RESUME_AUTO_GENERATION, generation);
        context.startForegroundService(intent);
    }

    static void cancelExplicitAutoResume() {
        synchronized (AUTO_RESUME_ORDER_LOCK) {
            PENDING_AUTO_RESUME_GENERATION.set(0L);
            pendingAutoResumeIngressElapsedRealtime = 0L;
        }
    }

    private void tryResumeAutomaticControl() {
        final long automaticToken;
        final long ingressElapsedRealtime;
        synchronized (AUTO_RESUME_ORDER_LOCK) {
            long generation = PENDING_AUTO_RESUME_GENERATION.get();
            if (destroyed || generation == 0L
                    || MANUAL_AUTO_GATE.hasPendingManualCommands()) {
                return;
            }
            automaticToken = MANUAL_AUTO_GATE.beginAutomaticDecision();
            if (automaticToken == ManualAutoGate.INVALID_AUTOMATIC_TOKEN
                    || !PENDING_AUTO_RESUME_GENERATION.compareAndSet(generation, 0L)) {
                return;
            }
            ingressElapsedRealtime = pendingAutoResumeIngressElapsedRealtime;
            pendingAutoResumeIngressElapsedRealtime = 0L;
            automaticOwnershipToken = automaticToken;
        }
        externalOffOverrideActive = false;
        externalOffOverrideRevision++;
        lastManualIntentFenceElapsedRealtime = ingressElapsedRealtime;
        invalidateAutomaticCommits("explicit auto-light enable");
        driveDecisionRevision++;
        timerHandler.removeCallbacks(driveFallbackRunnable);
        timerHandler.removeCallbacks(canbusReassertRunnable);
        timerHandler.removeCallbacks(forceInitRunnable);
        clearPendingExternalOff();
        automaticDecisionDeferredByExternalOff = false;
        forceInitCarSignalEpoch = 0L;
        forceInitCompleted = false;
        reassertKnownTargetPending = false;
        everSent = false;
        SensorApplyRequest pending = pendingMainSensorApply;
        if (pending != null) completeSensorApplyOnMain(pending);

        requestCarSignalMaintenance(true);
        HeadlightCanTransport.requestRecovery(this);
        physicalWakeReconcilePending = true;
        reconcileAfterPhysicalEvent("explicit auto-light enable", 0L,
                ingressElapsedRealtime);
    }

    private static void onManualCommandClosed() {
        lastManualIntentFenceElapsedRealtime = SystemClock.elapsedRealtime();
        if (PENDING_AUTO_RESUME_GENERATION.get() == 0L) return;
        LightSensorService service;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            service = activeCleanupOwner.get();
        }
        if (service == null) return;
        Handler main = service.timerHandler;
        if (main != null) main.post(service::tryResumeAutomaticControl);
    }

    private void reconcileAfterPhysicalEvent(String reason) {
        long protectionStarted = currentPhysicalWakeProtectionStart();
        long decisionStarted = pendingPhysicalReconcileDecisionStartedElapsed;
        if (decisionStarted == 0L) {
            decisionStarted = protectionStarted > 0L
                    ? protectionStarted : SystemClock.elapsedRealtime();
        }
        reconcileAfterPhysicalEvent(reason, protectionStarted, decisionStarted);
    }

    private long currentPhysicalWakeProtectionStart() {
        long generation = physicalWakeProtectionGeneration;
        if (generation < 0L || physicalWakeProtectionStartedElapsed == 0L
                || !ApplyEngine.isPhysicalWakeGenerationActive(generation)) {
            physicalWakeProtectionGeneration = -1L;
            physicalWakeProtectionStartedElapsed = 0L;
            return 0L;
        }
        return physicalWakeProtectionStartedElapsed;
    }

    private boolean consumePhysicalWakeRequest(long requestedGeneration) {
        final long requestGeneration;
        final long ingressElapsedRealtime;
        final long applyGeneration;
        synchronized (PHYSICAL_WAKE_ORDER_LOCK) {
            requestGeneration = pendingPhysicalWakeRequest;
            if (requestGeneration == 0L
                    || (requestedGeneration != 0L
                    && requestedGeneration != requestGeneration)) {
                return false;
            }
            ingressElapsedRealtime = pendingPhysicalWakeIngressElapsedRealtime;
            applyGeneration = pendingPhysicalWakeApplyGeneration;
            pendingPhysicalWakeRequest = 0L;
            pendingPhysicalWakeIngressElapsedRealtime = 0L;
            pendingPhysicalWakeApplyGeneration = 0L;
        }
        if (destroyed || !ApplyEngine.isPhysicalWakeGenerationActive(applyGeneration)) {
            return false;
        }
        physicalWakeProtectionGeneration = applyGeneration;
        physicalWakeProtectionStartedElapsed = ingressElapsedRealtime;
        clearPendingExternalOff();
        automaticDecisionDeferredByExternalOff = false;
        requestCarSignalMaintenance(true);
        HeadlightCanTransport.requestRecovery(this);
        reassertKnownTargetPending |= everSent && isAutomaticControlOwned();
        physicalWakeReconcilePending = true;
        reconcileAfterPhysicalEvent("physical wake", ingressElapsedRealtime,
                ingressElapsedRealtime);
        return true;
    }

    private void reconcileAfterPhysicalEvent(String reason,
                                             long preFrameOffProtectionStartedElapsed) {
        long decisionStartedElapsed = preFrameOffProtectionStartedElapsed > 0L
                ? preFrameOffProtectionStartedElapsed : SystemClock.elapsedRealtime();
        reconcileAfterPhysicalEvent(reason, preFrameOffProtectionStartedElapsed,
                decisionStartedElapsed);
    }

    private void reconcileAfterPhysicalEvent(String reason,
                                             long preFrameOffProtectionStartedElapsed,
                                             long decisionStartedElapsed) {
        if (destroyed) return;
        if (preFrameOffProtectionStartedElapsed > 0L) {
            if (externalOffOverrideActive || MANUAL_AUTO_GATE.blocksAntiAuto()
                    || hasManualOwnershipFence()) {
                forceInitCompleted = true;
                return;
            }
            Boolean protectedTarget = reasonToDesired(lastReason);
            CommitRequest current = currentAutomaticCommit();
            if (protectedTarget == null && current != null) {
                protectedTarget = current.targetOn;
            }
            if (protectedTarget == null && reassertKnownTargetPending) {
                protectedTarget = headlightsOn;
            }
            if (protectedTarget != null) {
                physicalWakeReconcilePending = false;
                reassertKnownTargetPending = false;
                timerHandler.removeCallbacks(forceInitRunnable);
                forceInitCarSignalEpoch = 0L;
                if (current != null && current.targetOn == protectedTarget
                        && ApplyEngine.isPhysicalWakeGenerationActive(
                        current.physicalWakeGeneration)
                        && (current.frameAttemptedElapsed == 0L
                        || current.frameAttemptedElapsed >= preFrameOffProtectionStartedElapsed)) {
                    current.preFrameOffProtectionStartedElapsed =
                            preFrameOffProtectionStartedElapsed;
                    current.preFrameOffProtectionEndedElapsed =
                            current.frameAttemptedElapsed;
                    current.protectPreFrameOff = current.frameAttemptedElapsed == 0L;
                    lastAutomaticDecisionStartedElapsed = Math.max(
                            lastAutomaticDecisionStartedElapsed, decisionStartedElapsed);
                } else {
                    commit(protectedTarget, reason + " reassert", false, 0L,
                            preFrameOffProtectionStartedElapsed, decisionStartedElapsed);
                }
                return;
            }
            // There is no automatic target to protect yet. Continue with the normal bounded
            // reconciliation path; a real OFF must remain authoritative until a target exists.
            preFrameOffProtectionStartedElapsed = 0L;
        }
        long epoch = readyCarSignalEpoch;
        if (epoch == 0L || epoch != activeCarSignalEpoch) {
            physicalWakeReconcilePending = true;
            pendingPhysicalReconcileDecisionStartedElapsed = Math.max(
                    pendingPhysicalReconcileDecisionStartedElapsed,
                    decisionStartedElapsed);
            return;
        }
        boolean reassertKnownTarget = reassertKnownTargetPending;
        physicalWakeReconcilePending = false;
        pendingPhysicalReconcileDecisionStartedElapsed = 0L;
        reassertKnownTargetPending = false;
        timerHandler.removeCallbacks(forceInitRunnable);
        forceInitCarSignalEpoch = 0L;
        if (externalOffOverrideActive || MANUAL_AUTO_GATE.blocksAntiAuto()) {
            forceInitCompleted = true;
            return;
        }
        if (hasManualOwnershipFence()) {
            // A completed manual LOW/OFF command invalidates ownership of the cached automatic
            // target. Wake/reconnect events may restore transport, but only a fresh RSM decision
            // is allowed to resume automatic control.
            forceInitCompleted = true;
            return;
        }
        Boolean desired = reasonToDesired(lastReason);
        if (desired == null && reassertKnownTarget) desired = headlightsOn;
        if (desired != null) {
            CommitRequest current = currentAutomaticCommit();
            if (current != null && current.targetOn == desired) {
            } else if (reassertKnownTarget || !everSent || headlightsOn != desired) {
                commit(desired, reason + (reassertKnownTarget ? " reassert" : ""),
                        false, 0L, 0L, decisionStartedElapsed);
            } else if (everSent && headlightsOn == desired) {
                markForceInitSatisfied();
            }
        } else {
            requestSensorLevelForApply(
                    epoch, SensorApplyMode.FORCE, reason, true, false, 0L,
                    decisionStartedElapsed);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (CALLBACK_CLEANUP_LOCK) {
            activeCleanupOwner = new WeakReference<>(this);
        }
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.i(TAG, "onCreate() — LightSensorService (CAN-coalesced + safety-watchdog)");
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        timerHandler = new Handler(Looper.getMainLooper());
        carSignalIoThread = new HandlerThread("CarSignalIo");
        carSignalIoThread.start();
        carSignalIoHandler = new Handler(carSignalIoThread.getLooper());
        carSignalIoExecutor = command -> {
            Handler io = carSignalIoHandler;
            if (io == null || !io.post(command)) {
                if (!destroyed) Log.w(TAG, "CarSignal ServiceConnection callback dropped");
            }
        };
        sensorCallbackDelivery = new LatestIntDelivery(command -> {
            if (!timerHandler.post(command)) {
                throw new RejectedExecutionException("main Handler stopped");
            }
        }, this::acceptSensorCallbackLevel);
        HeadlightCanTransport.setReadyListener(headlightReadyListener);
        HeadlightCanTransport.initialize(this);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Автосвет")
                .setContentText("Управление фарами активно")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(2, notification);

        IntentFilter reqFilter = new IntentFilter(ACTION_REQUEST_LUX_UPDATE);
        ContextCompat.registerReceiver(this, requestReceiver, reqFilter, BIND_PERMISSION, null,
                ContextCompat.RECEIVER_EXPORTED);

        requestCarSignalMaintenance(true);
        canBusSubscription = CanBusEventHub.get(this).subscribe(
                CanBusEventRouter.INTEREST_CONNECTION
                        | CanBusEventRouter.INTEREST_LIGHT_STATUS
                        | CanBusEventRouter.INTEREST_GEAR
                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{RSM_LIGHT_SW_REASON}, timerHandler, this::onCanBusEvent);
        timerHandler.postDelayed(safetyRunnable, SAFETY_POLL_INITIAL_DELAY_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand() action=" + action);
        if (ACTION_PHYSICAL_WAKE.equals(action)) {
            consumePhysicalWakeRequest(
                    intent.getLongExtra(EXTRA_PHYSICAL_WAKE_GENERATION, 0L));
        } else if (ACTION_RESUME_AUTO.equals(action)) {
            long generation = intent.getLongExtra(EXTRA_RESUME_AUTO_GENERATION, 0L);
            if (generation > 0L && PENDING_AUTO_RESUME_GENERATION.get() == generation) {
                tryResumeAutomaticControl();
            }
        } else if (PENDING_AUTO_RESUME_GENERATION.get() != 0L) {
            // START_STICKY recreation after an explicit enable must finish the same causal event;
            // it does not create a timer or a new automatic retry scope.
            tryResumeAutomaticControl();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy() — headlightsOn=" + headlightsOn);
        destroyed = true;
        synchronized (CALLBACK_CLEANUP_LOCK) {
            if (activeCleanupOwner.get() == this) {
                activeCleanupOwner = new WeakReference<>(null);
            }
        }
        settingsRequestGate.close();
        carSignalBindRetryBudget.close();
        callbackRegistrationRetryBudget.close();
        activeCommitSequence = ++nextCommitSequence;
        lightDecisionRevision++;
        pendingCommit = null;
        retryCommit = null;
        HeadlightCanTransport.clearReadyListener(headlightReadyListener);
        LatestIntDelivery sensorDelivery = sensorCallbackDelivery;
        sensorCallbackDelivery = null;
        if (sensorDelivery != null) sensorDelivery.close();
        CanBusEventHub.Subscription subscription = canBusSubscription;
        canBusSubscription = null;
        if (subscription != null) subscription.close();
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        timerHandler.removeCallbacks(forceInitRunnable);
        timerHandler.removeCallbacks(safetyRunnable);
        timerHandler.removeCallbacks(sensorDebounceRunnable);
        timerHandler.removeCallbacks(canbusReassertRunnable);
        timerHandler.removeCallbacks(driveFallbackRunnable);
        timerHandler.removeCallbacks(externalOffAdjudicationRunnable);
        pendingExternalOff = null;
        Handler io = carSignalIoHandler;
        HandlerThread ioThread = carSignalIoThread;
        if (io != null && ioThread != null) {
            if (!io.post(() -> {
                releaseCarSignalBindingOnIo("onDestroy");
                ioThread.quitSafely();
            })) {
                ioThread.quitSafely();
            }
        }
        timerHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // Срабатывает, когда значение датчика «устоялось» (дебаунс) — обрабатываем
    // последний полученный уровень.
    private final Runnable sensorDebounceRunnable = new Runnable() {
        @Override
        public void run() {
            CarSignalCallbackBinder callback = activeCarSignalCallback;
            if (pendingSensorLevel < 0 || pendingSensorEpoch != readyCarSignalEpoch
                    || pendingSensorEpoch != activeCarSignalEpoch || callback == null
                    || callback.epoch != pendingSensorEpoch
                    || callback.ingressRevision.get() != pendingSensorRevision) {
                return;
            }
            long epoch = pendingSensorEpoch;
            long revision = pendingSensorRevision;
            int level = pendingSensorLevel;
            onSensorLevel(epoch, revision, level, "callback");
            SensorApplyRequest apply = pendingMainSensorApply;
            if (apply != null && apply.epoch == epoch) {
                if (applySensorRequest(apply, level, revision, 0L)) {
                    completeSensorApplyOnMain(apply);
                }
            }
        }
    };

    // Один раз через FORCE_INIT_MS после готовности подписок принудительно выставляем таргет
    // (уличный если известен, иначе фолбэк на салонный) — гарантия установки на холодном старте.
    private final Runnable forceInitRunnable = new Runnable() {
        @Override
        public void run() {
            if (forceInitCompleted) return;
            if (!MANUAL_AUTO_GATE.isRevisionCurrent(forceInitManualRevision)) return;
            if (externalOffOverrideActive) return;
            if (hasManualOwnershipFence()) return;
            long epoch = forceInitCarSignalEpoch;
            if (epoch == 0L || epoch != readyCarSignalEpoch
                    || epoch != activeCarSignalEpoch) {
                return;
            }
            forceInitCarSignalEpoch = 0L;
            Boolean currentDesired = reasonToDesired(lastReason);
            if (currentDesired != null && isTargetAppliedOrCurrent(currentDesired)) {
                if (everSent) forceInitCompleted = true;
                return;
            }
            if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
                Log.i(TAG, "force-init: OEM Auto выбран с руля — инициализация отменена");
                forceInitCompleted = true;
                return;
            }
            if (reasonToDesired(lastReason) != null) {
                applyTargetWithSensorLevel("force-init", -1, true, null,
                        false, 0L, forceInitDecisionStartedElapsed);
            } else {
                requestSensorLevelForApply(
                        epoch, SensorApplyMode.FORCE, "force-init", true,
                        false, 0L, forceInitDecisionStartedElapsed);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Internal safety watchdog. Incoming CAN events never schedule/rearm this runnable.
    // -------------------------------------------------------------------------

    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                runSafetyWatchdog();
            } finally {
                if (!destroyed) timerHandler.postDelayed(this, SAFETY_POLL_MS);
            }
        }
    };

    private void runSafetyWatchdog() {
        // Re-open only the finite recovery budgets from this independent watchdog tick. A CAN
        // callback never calls this method, so an input storm cannot multiply Binder/CAN work.
        requestCarSignalMaintenance(true);
        HeadlightCanTransport.requestRecovery(this);

        long now = SystemClock.elapsedRealtime();
        long decisionStartedElapsed = lastAutomaticDecisionStartedElapsed > 0L
                ? lastAutomaticDecisionStartedElapsed : now;
        Boolean desired = reasonToDesired(lastReason);
        Boolean watchdogTarget = desired;
        if (watchdogTarget == null && everSent && isAutomaticControlOwned()) {
            // In the hysteresis/unknown RSM zone the last committed automatic target is still
            // the target. Keep enforcing it rather than waiting for a delta-only light event.
            watchdogTarget = headlightsOn;
        }
        if (!externalOffOverrideActive && !hasManualOwnershipFence()
                && !MANUAL_AUTO_GATE.blocksAntiAuto() && watchdogTarget != null
                && !hasCurrentCommitForTarget(watchdogTarget)) {
            // Periodically reassert even when our cached target is unchanged: the physical BCM
            // may have returned to Auto without delivering a usable delta event. Preserve the
            // original decision timestamp so a causally newer manual OFF still wins.
            commit(watchdogTarget, "safety-watchdog reassert", false, 0L, 0L,
                    decisionStartedElapsed);
        } else if (!externalOffOverrideActive && !hasManualOwnershipFence()
                && !MANUAL_AUTO_GATE.blocksAntiAuto() && desired == null && !everSent) {
            long epoch = readyCarSignalEpoch;
            if (epoch != 0L && epoch == activeCarSignalEpoch) {
                requestSensorLevelForApply(
                        epoch, SensorApplyMode.IF_UNSENT, "safety-watchdog", true,
                        false, 0L, now);
            }
        }

        // Preserve the original periodic TX36 snapshot for UI/fallback recovery. It is one
        // single-flight request per watchdog tick and is never scheduled by a CAN callback.
        long epoch = readyCarSignalEpoch;
        if (epoch != 0L && epoch == activeCarSignalEpoch
                && (desired != null || everSent)) {
            Handler io = carSignalIoHandler;
            if (io != null) {
                io.post(() -> {
                    if (!destroyed && epoch == carSignalEpoch && carSignalConnected) {
                        requestSensorLevelOnIo(null);
                    }
                });
            }
        }
    }

    /**
     * Показание салонного датчика (из колбэка/начального снимка/watchdog) — для индикации в UI.
     * Решения по фарам принимает уличный датчик (lightSWReason) + анти-Auto по Drive/старту;
     * салонный используется лишь как ФОЛБЭК внутри {@link #applyTargetWithSensorLevel},
     * а не непрерывно.
     */
    private void onSensorLevel(long epoch, long revision, int level, String source) {
        if (epoch != readyCarSignalEpoch || epoch != activeCarSignalEpoch) return;
        lastSensorEpoch = epoch;
        lastSensorRevision = revision;
        lastSensorLevel = level;
        broadcastUpdate(level);
    }

    private boolean applySensorRequest(SensorApplyRequest request, int level,
                                       long sensorRevision, long settingsGeneration) {
        if (!MANUAL_AUTO_GATE.isRevisionCurrent(request.manualRevision)) return true;
        if (externalOffOverrideActive || hasManualOwnershipFence()) return true;
        if (request.requiresDrive && (lastGear != GEAR_DRIVE
                || request.driveRevision != driveDecisionRevision)) {
            return true;
        }
        if (request.cancelOnManualAuto && MANUAL_AUTO_GATE.blocksAntiAuto()) {
            Log.i(TAG, request.reason + ": OEM Auto выбран с руля — pending action отменён");
            if (request.mode == SensorApplyMode.FORCE) forceInitCompleted = true;
            return true;
        }
        if (request.mode == SensorApplyMode.IF_UNSENT && everSent) return true;
        LightThresholds thresholds = null;
        if (reasonToDesired(lastReason) == null) {
            SettingsSnapshot snapshot = pendingSettingsSnapshot;
            if (snapshot == null || snapshot.request != request) {
                requestSettingsForApply(request);
                return false;
            }
            if (!snapshot.sensorFence.accepts(sensorRevision, settingsGeneration)) {
                Log.i(TAG, request.reason + ": sensor sample predates thresholds — waiting");
                return false;
            }
            thresholds = snapshot.thresholds;
            pendingSettingsSnapshot = null;
        }
        return applyTargetWithSensorLevel(
                request.reason, level, request.mode == SensorApplyMode.FORCE, thresholds,
                request.requiresDrive, request.driveRevision,
                request.decisionStartedElapsed);
    }

    /**
     * Выставить целевой режим фар: приоритет — уличный датчик (последний lightSWReason);
     * если данных улицы нет — фолбэк на салонный уровень по порогам.
     */
    private boolean applyTargetWithSensorLevel(String src, int sensorLevel) {
        return applyTargetWithSensorLevel(src, sensorLevel, false, null, false, 0L);
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget) {
        return applyTargetWithSensorLevel(
                src, sensorLevel, retainCurrentTarget, null, false, 0L);
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget,
                                               LightThresholds thresholds) {
        return applyTargetWithSensorLevel(
                src, sensorLevel, retainCurrentTarget, thresholds, false, 0L);
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget,
                                               LightThresholds thresholds,
                                               boolean requiresDrive, long driveRevision) {
        return applyTargetWithSensorLevel(src, sensorLevel, retainCurrentTarget, thresholds,
                requiresDrive, driveRevision, SystemClock.elapsedRealtime());
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget,
                                               LightThresholds thresholds,
                                               boolean requiresDrive, long driveRevision,
                                               long decisionStartedElapsed) {
        Boolean desired = reasonToDesired(lastReason);
        String s2 = src + " ext reason=" + lastReason;
        if (desired == null) {
            if (thresholds == null) {
                Log.i(TAG, src + ": thresholds pending — decision deferred");
                return false;
            }
            desired = thresholds.desiredFor(sensorLevel);
            s2 = src + " cabin level=" + sensorLevel;
        }
        if (desired == null && retainCurrentTarget && everSent) {
            desired = headlightsOn;
            s2 = src + " retain=" + (headlightsOn ? "low" : "off");
        }
        if (desired == null) {
            Log.i(TAG, src + ": нет данных (reason=" + lastReason + ") — не трогаем");
            return false;
        }
        Log.i(TAG, s2 + " → " + (desired ? "ближний" : "выкл"));
        return commit(desired, s2, requiresDrive, driveRevision, 0L,
                decisionStartedElapsed);
    }

    private boolean commit(boolean targetOn, String reason) {
        return commit(targetOn, reason, false, 0L, 0L);
    }

    private boolean commit(boolean targetOn, String reason,
                           boolean requiresDrive, long driveRevision) {
        return commit(targetOn, reason, requiresDrive, driveRevision, 0L);
    }

    private boolean commit(boolean targetOn, String reason,
                           boolean requiresDrive, long driveRevision,
                           long preFrameOffProtectionStartedElapsed) {
        return commit(targetOn, reason, requiresDrive, driveRevision,
                preFrameOffProtectionStartedElapsed, SystemClock.elapsedRealtime());
    }

    private boolean commit(boolean targetOn, String reason,
                           boolean requiresDrive, long driveRevision,
                           long preFrameOffProtectionStartedElapsed,
                           long decisionStartedElapsed) {
        if (pendingExternalOff != null) {
            automaticDecisionDeferredByExternalOff = true;
            Log.i(TAG, "auto-light decision deferred while external OFF is adjudicated");
            return false;
        }
        if (externalOffOverrideActive) {
            Log.i(TAG, "auto-light decision suppressed by external OFF until fresh light input");
            return false;
        }
        Log.i(TAG, "★ commit(" + (targetOn ? "ближний" : "выкл") + ") — " + reason);
        final long automaticToken = MANUAL_AUTO_GATE.beginAutomaticDecision();
        if (automaticToken == ManualAutoGate.INVALID_AUTOMATIC_TOKEN) {
            Log.i(TAG, "auto-light decision suppressed by queued manual command");
            return false;
        }
        automaticOwnershipToken = automaticToken;
        lastAutomaticDecisionStartedElapsed = decisionStartedElapsed;
        long decisionRevision = ++lightDecisionRevision;
        long physicalWakeGeneration = ApplyEngine.capturePhysicalWakeGeneration();
        CommitRequest request = new CommitRequest(
                ++nextCommitSequence, decisionRevision, automaticToken, physicalWakeGeneration,
                activeCanBusEpoch, requiresDrive, driveRevision, targetOn, reason,
                decisionStartedElapsed, preFrameOffProtectionStartedElapsed);
        activeCommitSequence = request.sequence;
        if (retryCommit != null) {
            retryCommit = null;
        }
        if (runningCommit != null) {
            pendingCommit = request;
        } else {
            dispatchCommit(request);
        }
        return true;
    }

    private void dispatchCommit(CommitRequest request) {
        if (destroyed || runningCommit != null || !isCommitCurrent(request)) return;
        runningCommit = request;
        AtomicBoolean operationEntered = new AtomicBoolean();
        AtomicLong frameAttemptedAt = new AtomicLong();
        AtomicLong frameAttemptIdentity = new AtomicLong();
        ApplyEngine.postWakeAction("auto light " + (request.targetOn ? "low" : "off"),
                () -> CanSender.runGuardedSend(
                        () -> isCommitCurrent(request),
                        () -> recordCommitFrameAttempt(
                                request, frameAttemptedAt, frameAttemptIdentity),
                        () -> {
                            operationEntered.set(true);
                            return MainActivity.setHeadlights(this, request.targetOn);
                        }),
                result -> {
                    Handler main = timerHandler;
                    if (main != null) {
                        main.post(() -> finishCommitOnMain(
                                request, result, operationEntered.get(),
                                frameAttemptedAt.get(), frameAttemptIdentity.get()));
                    }
                });
    }

    private void recordCommitFrameAttempt(CommitRequest request, AtomicLong requestAttempt,
                                          AtomicLong requestAttemptIdentity) {
        long now = SystemClock.elapsedRealtime();
        if (requestAttempt.compareAndSet(0L, now)) {
            long attemptIdentity = nextFrameAttemptIdentity.incrementAndGet();
            requestAttemptIdentity.set(attemptIdentity);
            request.frameAttemptedElapsed = now;
            request.frameAttemptIdentity = attemptIdentity;
            if (request.protectPreFrameOff
                    && request.preFrameOffProtectionStartedElapsed > 0L
                    && request.preFrameOffProtectionEndedElapsed == 0L) {
                request.preFrameOffProtectionEndedElapsed = now;
            }
            request.protectPreFrameOff = false;
            lastFrameAttemptElapsed = now;
            lastFrameAttemptIdentity = attemptIdentity;
            lastFrameAttemptRequestSequence = request.sequence;
            CanBusEventHub.Subscription subscription = canBusSubscription;
            if (subscription != null) subscription.forgetLightStatus();
            Handler main = timerHandler;
            if (main != null) {
                main.post(() -> clearExternalOffForNewFrameAttemptOnMain(
                        request, attemptIdentity, now));
            }
        }
    }

    private boolean isCommitCurrent(CommitRequest request) {
        if (destroyed || request == null || activeCommitSequence != request.sequence
                || lightDecisionRevision != request.decisionRevision
                || !MANUAL_AUTO_GATE.isAutomaticActionCurrent(request.automaticToken)
                || !ApplyEngine.isPhysicalWakeGenerationActive(
                request.physicalWakeGeneration)) {
            return false;
        }
        if (request.canBusEpoch != 0L && activeCanBusEpoch != request.canBusEpoch) return false;
        boolean driveOnly = request.requiresDrive;
        long capturedDriveRevision = request.driveRevision;
        if (driveOnly && (lastGear != GEAR_DRIVE
                || driveDecisionRevision != capturedDriveRevision)) {
            // A meaningful RSM edge may promote the same-target request while this worker is
            // checking its old Drive-only lifetime. Re-read the volatile pair before rejecting.
            if (request.requiresDrive && request.driveRevision == capturedDriveRevision) {
                return false;
            }
        }
        return true;
    }

    private void finishCommitOnMain(CommitRequest request,
                                    ApplyEngine.WakeActionResult result,
                                    boolean operationEntered, long frameAttemptedAt,
                                    long frameAttemptIdentity) {
        if (destroyed || runningCommit != request) return;
        runningCommit = null;
        if (frameAttemptedAt > 0L) lastCommitElapsed = frameAttemptedAt;
        if (frameAttemptIdentity > 0L) {
            request.terminalFrameAttemptIdentity = frameAttemptIdentity;
            request.terminalFrameResult = result;
        }

        boolean current = isCommitCurrent(request);
        if (current && result == ApplyEngine.WakeActionResult.SUCCESS) {
            lastSuccessfulCommit = request;
            headlightsOn = request.targetOn;
            everSent = true;
            markForceInitSatisfied();
            if (physicalWakeProtectionGeneration == request.physicalWakeGeneration
                    && frameAttemptedAt >= physicalWakeProtectionStartedElapsed) {
                physicalWakeProtectionGeneration = -1L;
                physicalWakeProtectionStartedElapsed = 0L;
            }
            if (frameAttemptedAt == 0L && CanSender.isDebugMode()) {
                lastCommitElapsed = SystemClock.elapsedRealtime();
            }
        } else if (current) {
            everSent = false;
        }

        CommitRequest next = pendingCommit;
        pendingCommit = null;
        if (next != null) {
            finishExternalOffAdjudicationForCommit(request, result);
            dispatchCommit(next);
            return;
        }
        if (current && result == ApplyEngine.WakeActionResult.FAILED && operationEntered) {
            scheduleCommitRetry(request);
        } else if (current && result != ApplyEngine.WakeActionResult.SUCCESS) {
            Log.w(TAG, "auto-light decision deferred until next real light/wake event");
        }
        finishExternalOffAdjudicationForCommit(request, result);
    }

    private void scheduleCommitRetry(CommitRequest request) {
        if (!isCommitCurrent(request)
                || request.retriesUsed >= MAX_COMMIT_RETRIES_PER_DECISION) {
            Log.w(TAG, "auto-light retry exhausted; waiting for next real light/wake event");
            return;
        }
        request.retriesUsed++;
        retryCommit = request;
        HeadlightCanTransport.awaitReadyAfterFailure(this);
    }

    private void retryCommitWhenTransportReady() {
        CommitRequest request = retryCommit;
        if (destroyed || request == null || runningCommit != null
                || !isCommitCurrent(request)) {
            if (request != null && !isCommitCurrent(request)) retryCommit = null;
            return;
        }
        PendingExternalOff candidate = pendingExternalOff;
        if (candidate != null && candidate.requestSequence == request.sequence) {
            Log.i(TAG, "auto-light retry waits for provisional external OFF adjudication");
            return;
        }
        retryCommit = null;
        dispatchCommit(request);
    }

    private void invalidateAutomaticCommits(String reason) {
        activeCommitSequence = ++nextCommitSequence;
        lightDecisionRevision++;
        pendingCommit = null;
        retryCommit = null;
        Log.i(TAG, "automatic light work invalidated: " + reason);
    }

    /**
     * Уличный датчик (BCM_RSM_lightSWReason, лобовой RSM) — основной источник автосвета.
     * 0 Day → выкл; 2 Dark / 3 Tunnel / 4 Darkstart → ближний; 1 Others — не меняем.
     */
    private void onLightSwReason(CanBusEvent event) {
        int reason = event.second;
        int previousReason = lastReason;
        lastReason = reason; // запоминаем последнее известное состояние улицы (для анти-Auto по Drive)
        Boolean desired = reasonToDesired(reason);
        Log.i(TAG, "RSM lightSWReason=" + reason + " → "
                + (desired == null ? "без изменений" : (desired ? "ближний" : "выкл")));
        if (event.elapsedRealtime <= lastManualIntentFenceElapsedRealtime) {
            Log.i(TAG, "RSM event predates newer manual light intent; automatic action skipped");
            return;
        }
        CommitRequest current = currentAutomaticCommit();
        long requestWakeStart = current != null
                ? current.preFrameOffProtectionStartedElapsed : 0L;
        long globalWakeStart = currentPhysicalWakeProtectionStart();
        long wakeStart = Math.max(requestWakeStart, globalWakeStart);
        if (wakeStart > 0L && event.elapsedRealtime <= wakeStart) {
            lastReason = previousReason;
            Log.i(TAG, "RSM replay predates protected physical wake; ignored");
            return;
        }
        if (wakeStart > 0L) {
            // Event time, not delivery time, closes the immutable pre-frame interval.  A later
            // frame marker must never widen this cutoff again.
            physicalWakeProtectionGeneration = -1L;
            physicalWakeProtectionStartedElapsed = 0L;
            if (current != null && requestWakeStart > 0L) {
                long ended = current.preFrameOffProtectionEndedElapsed;
                if (ended == 0L || event.elapsedRealtime < ended) {
                    current.preFrameOffProtectionEndedElapsed = event.elapsedRealtime;
                }
                current.protectPreFrameOff = false;
            }
        }
        clearPendingExternalOffIfSuperseded(event);
        automaticDecisionDeferredByExternalOff = false;
        if (desired == null) {
            invalidateAutomaticCommits("neutral RSM light reason " + reason);
            return;
        }
        // A meaningful RSM edge is the next real illumination decision and therefore releases a
        // confirmed external-OFF override. A buffered pre-ready replay predating the current
        // decision is state reconstruction, not a new illumination edge, and cannot release it.
        if (externalOffOverrideActive
                && event.elapsedRealtime <= lastAutomaticDecisionStartedElapsed) {
            Log.i(TAG, "RSM replay predates external-OFF fence; manual state preserved");
            return;
        }
        externalOffOverrideActive = false;
        externalOffOverrideRevision++;
        timerHandler.removeCallbacks(driveFallbackRunnable);
        SensorApplyRequest pendingApply = pendingMainSensorApply;
        if (pendingApply != null) {
            // Fresh outdoor state is authoritative; no stale thresholds/TX36 result may publish
            // a second decision after it.
            completeSensorApplyOnMain(pendingApply);
        }
        current = currentAutomaticCommit();
        if (current != null && current.targetOn == desired) {
            // Dark→Tunnel and equivalent same-target edges adopt the already queued command.  In
            // particular, do not make a frame which already entered TX58 stale and send it twice.
            // A real RSM decision also promotes an older Drive-only fallback to normal lifetime,
            // so leaving Drive cannot discard the only copy of this delta event.
            current.requiresDrive = false;
            current.driveRevision = 0L;
            lastAutomaticDecisionStartedElapsed = Math.max(
                    lastAutomaticDecisionStartedElapsed, event.elapsedRealtime);
            if (current == retryCommit) {
                HeadlightCanTransport.requestRecovery(this);
            }
            return;
        }
        if (current != null) {
            invalidateAutomaticCommits("RSM target changed to " + reason);
        }
        if (everSent && headlightsOn == desired && isAutomaticControlOwned()) {
            markForceInitSatisfied();
            return;
        }
        commit(desired, "ext-sensor reason=" + reason, false, 0L,
                0L, event.elapsedRealtime);
    }

    private void markForceInitSatisfied() {
        forceInitCompleted = true;
        forceInitCarSignalEpoch = 0L;
        timerHandler.removeCallbacks(forceInitRunnable);
    }

    private boolean isTargetAppliedOrCurrent(boolean targetOn) {
        CommitRequest current = currentAutomaticCommit();
        if (current != null) return current.targetOn == targetOn;
        return everSent && headlightsOn == targetOn;
    }

    private boolean hasCurrentCommitForTarget(boolean targetOn) {
        CommitRequest current = currentAutomaticCommit();
        return current != null && current.targetOn == targetOn;
    }

    private CommitRequest currentAutomaticCommit() {
        if (pendingCommit != null && isCommitCurrent(pendingCommit)) return pendingCommit;
        if (runningCommit != null && isCommitCurrent(runningCommit)) return runningCommit;
        if (retryCommit != null && isCommitCurrent(retryCommit)) return retryCommit;
        return null;
    }

    private boolean isAutomaticControlOwned() {
        return MANUAL_AUTO_GATE.isAutomaticActionCurrent(automaticOwnershipToken);
    }

    private boolean hasManualOwnershipFence() {
        return MANUAL_AUTO_GATE.currentRevision() != 0L && !isAutomaticControlOwned();
    }

    /** RSM lightSWReason → цель: 0 Day→выкл, 2/3/4 Dark/Tunnel/Darkstart→ближний, иначе null. */
    private Boolean reasonToDesired(int reason) {
        switch (reason) {
            case 0: return Boolean.FALSE;                     // день → выкл
            case 2: case 3: case 4: return Boolean.TRUE;      // темно/тоннель → ближний
            default: return null;                             // 1 Others / неизвестно
        }
    }

    /**
     * Перевод КПП в Drive: BCM сам сбрасывает фары в Auto (матрица). Если освещённость не меняется,
     * lightSWReason не придёт и мы застрянем в Auto — поэтому через {@link #DRIVE_FALLBACK_MS}
     * принудительно выставляем таргет.
     */
    private void onGear(CanBusEvent event) {
        int gearVal = event.first;
        if (gearVal < 0 || gearVal == lastGear) return;
        boolean toDrive = (gearVal == GEAR_DRIVE);
        boolean hadCurrentDriveWork = hasCurrentDriveWork();
        driveDecisionRevision++;
        lastGear = gearVal;
        timerHandler.removeCallbacks(driveFallbackRunnable);
        if (toDrive) {
            if (externalOffOverrideActive) return;
            if (hasManualOwnershipFence()) return;
            Log.i(TAG, "gear=Drive → через " + DRIVE_FALLBACK_MS + "мс выставим таргет (анти-Auto)");
            driveFallbackManualRevision = MANUAL_AUTO_GATE.currentRevision();
            driveFallbackDecisionStartedElapsed = event.elapsedRealtime;
            timerHandler.postDelayed(driveFallbackRunnable, DRIVE_FALLBACK_MS);
        } else if (hadCurrentDriveWork) {
            Boolean desired = reasonToDesired(lastReason);
            if (desired != null && !isTargetAppliedOrCurrent(desired)) {
                commit(desired, "gear exit, current RSM reason=" + lastReason,
                        false, 0L, 0L, event.elapsedRealtime);
            }
        }
    }

    private boolean hasCurrentDriveWork() {
        SensorApplyRequest apply = pendingMainSensorApply;
        if (apply != null && apply.requiresDrive && apply.driveRevision == driveDecisionRevision
                && lastGear == GEAR_DRIVE
                && MANUAL_AUTO_GATE.isRevisionCurrent(apply.manualRevision)) {
            return true;
        }
        return isCurrentDriveCommit(runningCommit)
                || isCurrentDriveCommit(pendingCommit)
                || isCurrentDriveCommit(retryCommit);
    }

    private boolean isCurrentDriveCommit(CommitRequest request) {
        return request != null && request.requiresDrive && isCommitCurrent(request);
    }

    // Анти-Auto после Drive: выставляем таргет по уличному датчику (если знаем), иначе по салонному.
    private final Runnable driveFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastGear != GEAR_DRIVE) return;
            if (!MANUAL_AUTO_GATE.isRevisionCurrent(driveFallbackManualRevision)) return;
            if (externalOffOverrideActive) return;
            if (hasManualOwnershipFence()) return;
            long driveRevision = driveDecisionRevision;
            if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
                Log.i(TAG, "drive+5s: OEM Auto выбран с руля — anti-Auto пропущен");
                return;
            }
            if (reasonToDesired(lastReason) != null) {
                applyTargetWithSensorLevel("drive+5s (анти-Auto)", -1,
                        false, null, true, driveRevision,
                        driveFallbackDecisionStartedElapsed);
                return;
            }
            long epoch = readyCarSignalEpoch;
            if (epoch != 0L && epoch == activeCarSignalEpoch) {
                requestSensorLevelForApply(
                        epoch, SensorApplyMode.FORCE, "drive+5s (анти-Auto)", true,
                        true, driveRevision, driveFallbackDecisionStartedElapsed);
            }
        }
    };

    /**
     * Статус фар из CanBusService (LightStatus). Ловим внешний сброс режима: при
     * переводе КПП в Drive BCM уходит в «авто» (autoLamp=1). Если наш таргет —
     * «ближний» (темно), возвращаем ближний. Ручное «выкл» (autoLamp=0, headLight=0)
     * под правило не попадает — уважается. Guard отсекает эхо своих команд.
     */
    private void onLightStatusChanged(CanBusEvent event) {
        int autoLamp = event.first;
        int dippedBeam = event.second;
        int headLight = event.third;
        boolean clearedProvisionalOff = false;
        PendingExternalOff deferredOff = pendingExternalOff;
        if (deferredOff != null && event.sequence != deferredOff.eventSequence
                && !deferredOff.samePayload(event)) {
            clearPendingExternalOff();
            clearedProvisionalOff = true;
        }
        // Фильтр шума: реагируем только на изменение значимых полей
        // (поворотники/стоп меняют другие поля и сыпят событиями постоянно).
        if (autoLamp == lastAutoLamp && dippedBeam == lastDippedBeam
                && headLight == lastHeadLight
                && lastLightStatusEvaluatedFrameIdentity == lastFrameAttemptIdentity) {
            return;
        }
        long recentAttemptAt = Math.max(lastCommitElapsed, lastFrameAttemptElapsed);
        long since = recentAttemptAt == 0L
                ? Long.MAX_VALUE : event.elapsedRealtime - recentAttemptAt;
        CommitRequest current = currentAutomaticCommit();
        CommitRequest frameOwner = current != null ? current : lastSuccessfulCommit;
        long policyAttemptAt = frameOwner != null ? frameOwner.frameAttemptedElapsed : 0L;
        long policyDecisionStartedAt = frameOwner != null
                ? Math.max(lastAutomaticDecisionStartedElapsed,
                frameOwner.decisionStartedElapsed)
                : lastAutomaticDecisionStartedElapsed;
        boolean policyAttemptTargetOn = frameOwner != null && frameOwner.targetOn;
        boolean protectedBeforeFrame = frameOwner != null
                && frameOwner.protectsOffEventBeforeFrame(event.elapsedRealtime);
        // A confirmed external OFF supersedes work queued from an older sensor snapshot. Other
        // status changes (including the echo of an in-flight LOW_BEAM) must not cancel that
        // decision before its terminal callback records the result.
        LightStatusEventPolicy.Decision externalOffDecision =
                LightStatusEventPolicy.classifyExternalOff(
                autoLamp, headLight, event.elapsedRealtime,
                policyDecisionStartedAt, policyAttemptAt, policyAttemptTargetOn,
                protectedBeforeFrame,
                HEADLIGHT_GUARD_MS);
        if (externalOffDecision == LightStatusEventPolicy.Decision.CONFIRM) {
            boolean compensatePreFrameLow = frameOwner != null && frameOwner.targetOn
                    && frameOwner.frameAttemptedElapsed > event.elapsedRealtime;
            confirmExternalOff("status event", compensatePreFrameLow);
        } else if (externalOffDecision == LightStatusEventPolicy.Decision.DEFER) {
            scheduleExternalOffAdjudication(event, frameOwner);
        }
        lastAutoLamp = autoLamp; lastDippedBeam = dippedBeam; lastHeadLight = headLight;
        lastLightStatusEvaluatedFrameIdentity = lastFrameAttemptIdentity;
        if (clearedProvisionalOff && pendingExternalOff == null
                && externalOffDecision == LightStatusEventPolicy.Decision.IGNORE) {
            resumeDeferredAutomaticDecision(event.elapsedRealtime);
        }

        Log.i(TAG, "lightstatus: autoLamp=" + autoLamp + " dippedBeam=" + dippedBeam
                + " headLight=" + headLight
                + " ourTarget=" + (headlightsOn ? "ближний" : "выкл")
                + " sinceCommit=" + since + "ms");

        // Любое значимое изменение статуса отменяет отложенную переустановку —
        // решение принимаем заново по свежему состоянию.
        timerHandler.removeCallbacks(canbusReassertRunnable);

        if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
            Log.i(TAG, "lightstatus: OEM Auto выбран с руля — anti-Auto подавлен");
            return;
        }
        if (!everSent) return;                 // режим ещё не выставляли — ждём force-init
        if (!headlightsOn) return;             // таргет «выкл» (светло) — не вмешиваемся
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
            canbusReassertManualRevision = MANUAL_AUTO_GATE.currentRevision();
            canbusReassertDecisionStartedElapsed = event.elapsedRealtime;
            timerHandler.postDelayed(canbusReassertRunnable, CANBUS_REASSERT_DELAY_MS);
        }
    }

    private void clearExternalOffForNewFrameAttemptOnMain(
            CommitRequest request, long attemptIdentity, long attemptedAtElapsed) {
        PendingExternalOff candidate = pendingExternalOff;
        if (destroyed || candidate == null
                || candidate.frameAttemptIdentity == attemptIdentity
                || request.frameAttemptIdentity != attemptIdentity) return;
        // The OFF edge predates this newer physical TX58 attempt.  It cannot be carried across
        // the command and later used to undo that command; a newer OFF event will be classified
        // against the new exact frame identity.
        if (candidate.eventElapsedRealtime <= attemptedAtElapsed) clearPendingExternalOff();
    }

    private void scheduleExternalOffAdjudication(CanBusEvent event,
                                                 CommitRequest frameOwner) {
        if (frameOwner == null || frameOwner.frameAttemptIdentity == 0L
                || !ApplyEngine.isPhysicalWakeGenerationActive(
                frameOwner.physicalWakeGeneration)) {
            return;
        }
        long dueElapsedRealtime = frameOwner.frameAttemptedElapsed + HEADLIGHT_GUARD_MS;
        PendingExternalOff candidate = new PendingExternalOff(
                event, frameOwner.physicalWakeGeneration, frameOwner.decisionRevision,
                frameOwner.automaticToken, frameOwner.sequence,
                frameOwner.frameAttemptIdentity,
                dueElapsedRealtime,
                frameOwner.terminalFrameAttemptIdentity
                        == frameOwner.frameAttemptIdentity);
        pendingExternalOff = candidate;
        timerHandler.removeCallbacks(externalOffAdjudicationRunnable);
        long remaining = Math.max(0L, dueElapsedRealtime - SystemClock.elapsedRealtime());
        timerHandler.postDelayed(externalOffAdjudicationRunnable, remaining);
    }

    private void adjudicatePendingExternalOff() {
        PendingExternalOff candidate = pendingExternalOff;
        if (destroyed || candidate == null
                || !ApplyEngine.isPhysicalWakeGenerationActive(candidate.wakeGeneration)
                || lightDecisionRevision != candidate.decisionRevision
                || !MANUAL_AUTO_GATE.isAutomaticActionCurrent(candidate.automaticToken)
                || lastFrameAttemptIdentity != candidate.frameAttemptIdentity
                || lastFrameAttemptRequestSequence != candidate.requestSequence
                || lastAutoLamp != candidate.autoLamp
                || lastDippedBeam != candidate.dippedBeam
                || lastHeadLight != candidate.headLight) {
            clearPendingExternalOff();
            return;
        }
        if (!candidate.terminalKnown) {
            // The one timer elapsed while the exact Binder operation is still in flight. Keep
            // the candidate without another timer; the exact terminal path will decide once.
            CommitRequest running = runningCommit;
            CommitRequest retry = retryCommit;
            boolean exactRunning = running != null
                    && running.sequence == candidate.requestSequence
                    && running.frameAttemptIdentity == candidate.frameAttemptIdentity;
            boolean exactTerminal = retry != null
                    && retry.sequence == candidate.requestSequence
                    && retry.terminalFrameAttemptIdentity == candidate.frameAttemptIdentity;
            if (!exactRunning && !exactTerminal) {
                clearPendingExternalOff();
            }
            return;
        }
        confirmExternalOff("deferred status event", false);
    }

    private void finishExternalOffAdjudicationForCommit(
            CommitRequest request, ApplyEngine.WakeActionResult result) {
        PendingExternalOff candidate = pendingExternalOff;
        if (candidate == null || candidate.requestSequence != request.sequence
                || candidate.frameAttemptIdentity != request.frameAttemptIdentity) {
            return;
        }
        candidate.terminalKnown = true;
        if (SystemClock.elapsedRealtime() >= candidate.adjudicationDueElapsedRealtime) {
            adjudicatePendingExternalOff();
        }
    }

    private void confirmExternalOff(String source, boolean compensatePreFrameLow) {
        clearPendingExternalOff();
        automaticDecisionDeferredByExternalOff = false;
        externalOffOverrideActive = true;
        externalOffOverrideRevision++;
        Log.i(TAG, "confirmed external headlight OFF: " + source);
        cancelAutomaticWorkForExternalOff();
        if (compensatePreFrameLow) sendCompensatingExternalOff();
    }

    private void sendCompensatingExternalOff() {
        final long overrideRevision = externalOffOverrideRevision;
        final long manualRevision = MANUAL_AUTO_GATE.currentRevision();
        final long wakeGeneration = ApplyEngine.capturePhysicalWakeGeneration();
        final long canBusEpoch = activeCanBusEpoch;
        ApplyEngine.postWakeAction("compensate external headlight OFF",
                () -> CanSender.runGuardedSend(
                        () -> !destroyed && externalOffOverrideActive
                                && externalOffOverrideRevision == overrideRevision
                                && MANUAL_AUTO_GATE.isRevisionCurrent(manualRevision)
                                && activeCanBusEpoch == canBusEpoch
                                && ApplyEngine.isPhysicalWakeGenerationActive(wakeGeneration),
                        () -> MainActivity.setHeadlights(this, false)),
                result -> {
                    if (result != ApplyEngine.WakeActionResult.SUCCESS) {
                        Log.w(TAG, "external OFF compensation deferred until next real event");
                    }
                });
    }

    private void resumeDeferredAutomaticDecision(long decisionStartedElapsed) {
        if (!automaticDecisionDeferredByExternalOff || destroyed
                || pendingExternalOff != null || externalOffOverrideActive
                || hasManualOwnershipFence()) return;
        automaticDecisionDeferredByExternalOff = false;
        CommitRequest retry = retryCommit;
        if (retry != null && isCommitCurrent(retry)) {
            HeadlightCanTransport.requestRecovery(this);
            return;
        }
        SensorApplyRequest apply = pendingMainSensorApply;
        if (apply != null) {
            requestSettingsForApply(apply);
            return;
        }
        Boolean desired = reasonToDesired(lastReason);
        if (desired != null && !isTargetAppliedOrCurrent(desired)) {
            commit(desired, "status after provisional OFF", false, 0L, 0L,
                    decisionStartedElapsed);
        }
    }

    private void clearPendingExternalOffIfSuperseded(CanBusEvent event) {
        PendingExternalOff candidate = pendingExternalOff;
        if (candidate != null && (event.sequence > candidate.eventSequence
                || event.elapsedRealtime > candidate.eventElapsedRealtime)) {
            clearPendingExternalOff();
        }
    }

    private void clearPendingExternalOff() {
        pendingExternalOff = null;
        Handler handler = timerHandler;
        if (handler != null) handler.removeCallbacks(externalOffAdjudicationRunnable);
    }

    private void cancelAutomaticWorkForExternalOff() {
        invalidateAutomaticCommits("external headlight off");
        driveDecisionRevision++;
        timerHandler.removeCallbacks(driveFallbackRunnable);
        timerHandler.removeCallbacks(forceInitRunnable);
        forceInitCarSignalEpoch = 0L;
        forceInitCompleted = true;
        physicalWakeReconcilePending = false;
        reassertKnownTargetPending = false;
        everSent = false;
        headlightsOn = false;
        SensorApplyRequest pending = pendingMainSensorApply;
        if (pending != null) completeSensorApplyOnMain(pending);
    }

    // Отложенная переустановка ближнего после того как поймали «авто». Перед запуском
    // ещё раз проверяем актуальность (таргет ближний и BCM всё ещё в авто).
    private final Runnable canbusReassertRunnable = new Runnable() {
        @Override
        public void run() {
            if (!MANUAL_AUTO_GATE.isRevisionCurrent(canbusReassertManualRevision)) return;
            if (externalOffOverrideActive) return;
            if (!isAutomaticControlOwned()) return;
            if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
                Log.i(TAG, "canbus-reset: OEM Auto выбран с руля — отмена");
                return;
            }
            if (!everSent || !headlightsOn) return;
            if (lastAutoLamp != 1) {           // за время выдержки ушли из «авто» — отменяем
                Log.i(TAG, "canbus-reset: за выдержку состояние ушло из авто — отмена");
                return;
            }
            Log.i(TAG, "canbus-reset: выдержка прошла, BCM всё ещё в авто → возвращаем ближний");
            commit(true, "canbus-reset", false, 0L, 0L,
                    canbusReassertDecisionStartedElapsed);
        }
    };

    static ManualAutoGate.Ticket reserveManualHeadlightCommand() {
        lastManualIntentFenceElapsedRealtime = SystemClock.elapsedRealtime();
        synchronized (AUTO_RESUME_ORDER_LOCK) {
            ManualAutoGate.Ticket ticket = MANUAL_AUTO_GATE.reserveManualCommand(
                    LightSensorService::onManualCommandClosed);
            // A steering-wheel command accepted after an Enable intent wins over that older
            // asynchronous startService delivery. A later Enable publishes a new generation.
            PENDING_AUTO_RESUME_GENERATION.set(0L);
            pendingAutoResumeIngressElapsedRealtime = 0L;
            return ticket;
        }
    }

    /** Отмечает завершённый выбор OEM Auto; возвращает предыдущее значение для rollback. */
    static boolean setManualAutoOverride(boolean enabled) {
        return MANUAL_AUTO_GATE.setSelected(enabled);
    }

    // -------------------------------------------------------------------------
    // ContentProvider — настройки RestoreMode (один запрос за вызов)
    // -------------------------------------------------------------------------

    private static final Uri CONTENT_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");

    private static final int COL_THRESHOLD_ON = 9;
    private static final int COL_THRESHOLD_OFF = 10;

    private static LightThresholds queryThresholds(ContentResolver resolver) {
        int thresholdOn = LightThresholds.DEFAULT_ON;
        int thresholdOff = LightThresholds.DEFAULT_OFF;
        try {
            Cursor cursor = resolver.query(
                    CONTENT_PROVIDER_URI, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst() && cursor.getColumnCount() > COL_THRESHOLD_OFF) {
                        thresholdOn = cursor.getInt(COL_THRESHOLD_ON);
                        thresholdOff = cursor.getInt(COL_THRESHOLD_OFF);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryThresholds: " + e.getMessage() + " — defaults");
        }
        if (thresholdOn > thresholdOff) {
            Log.w(TAG, "queryThresholds: thresholds inverted — swapped");
        }
        return new LightThresholds(thresholdOn, thresholdOff);
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
