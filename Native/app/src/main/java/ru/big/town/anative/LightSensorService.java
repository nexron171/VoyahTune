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

import java.lang.ref.WeakReference;
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
 * Архитектура (event-driven + safety-poll), проверена декомпиляцией CarSignalService:
 *  - Подписка: регистрируем колбэк через TX=46 (writeStrongBinder). Сервис ONEWAY-ом
 *    вызывает onLightSensorChanged(level) — код 13, дескриптор
 *    "com.qinggan.carsignal.ICarSignalServiceCallBack". Это мгновенный push при
 *    изменении освещённости (уровень 0–7).
 *  - Начальный снимок: колбэки дельта-only (не отдают текущее значение), поэтому
 *    при коннекте читаем уровень через TX=36 (getLightSensorLevel) на отдельной
 *    single-flight очереди, не блокируя main и bind/reconnect.
 *  - Safety-poll каждые SAFETY_POLL_MS: фоновая страховка от пропущенного события.
 *    CAN шлёт только при реальной смене цели — холостого трафика не создаёт.
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
    private static final int    MAX_OUTSTANDING_CALLBACKS = 2;
    private static final AtomicInteger OUTSTANDING_CALLBACKS = new AtomicInteger();

    // Best-effort unregister is process-scoped and bounded: a stuck vendor TX47 can retain one
    // running call plus only the latest cleanup request, never a queue per reconnect/service start.
    private static final ThreadPoolExecutor CAR_SIGNAL_QUERY_EXECUTOR =
            newBoundedBinderExecutor("CarSignalQuery");
    private static final ThreadPoolExecutor CAR_SIGNAL_REGISTRATION_EXECUTOR =
            newBoundedBinderExecutor("CarSignalRegistration");
    private static final ThreadPoolExecutor CAR_SIGNAL_CLEANUP_EXECUTOR =
            newBoundedBinderExecutor("CarSignalCleanup");
    private static final ThreadPoolExecutor LIGHT_SETTINGS_EXECUTOR =
            newBoundedBinderExecutor("LightSettings");

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

    private Handler timerHandler;
    private volatile LatestIntDelivery sensorCallbackDelivery;
    private HandlerThread carSignalIoThread;
    private Handler carSignalIoHandler;
    private Executor carSignalIoExecutor;
    private final AtomicBoolean carSignalMaintenancePosted = new AtomicBoolean();
    private boolean sensorQueryRunning;
    private boolean sensorQueryRequested;
    private long nextSensorApplyGeneration;
    private SensorApplyRequest pendingIoSensorApply;
    private SensorApplyRequest pendingIoSettingsRequest;
    private long pendingIoSettingsGeneration;
    private SensorQueryRun runningSensorQuery;
    private IBinder carSignalBinder = null;
    private CarSignalCallbackBinder carSignalCallbackBinder;
    private boolean carSignalBindingRequested = false;
    private boolean carSignalConnected = false;
    private boolean callbackRegistered = false;
    private boolean callbackRegistrationInFlight = false;
    private RegisterRequest pendingRegistration;
    private RegisterRequest runningRegistration;
    private long    lastBindAttempt = -BIND_RETRY_MS;
    private final Runnable carSignalRebindRunnable = this::ensureBound;
    private long carSignalEpoch = 0L;
    private volatile long activeCarSignalEpoch = 0L;
    private volatile CarSignalCallbackBinder activeCarSignalCallback;
    private ServiceConnection carSignalConnection;
    private long nextCarSignalBindingGeneration;
    private long activeCarSignalBindingGeneration;

    // Текущая зафиксированная цель: true = ближний свет, false = наружный свет выключен
    private boolean headlightsOn = false;
    private boolean everSent     = false;
    private boolean forceInitCompleted = false;
    private long    readyCarSignalEpoch = 0L;
    private long    forceInitCarSignalEpoch = 0L;
    private long    commitSequence = 0L;
    private long    pendingSensorEpoch = 0L;
    private long    pendingSensorRevision = 0L;
    private int     pendingSensorLevel = -1;
    private long    lastCommitElapsed  = 0L;

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
    private int lastGear   = -1;
    private int lastReason = -1;

    private CanBusEventHub.Subscription canBusSubscription;
    private volatile boolean destroyed = false;
    // Последние значимые поля LightStatus — фильтр шума от поворотников/стопа
    private int lastAutoLamp   = -1;
    private int lastDippedBeam = -1;
    private int lastHeadLight  = -1;

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
        switch (event.kind) {
            case LIGHT_STATUS:
                onLightStatusChanged(event.first, event.second, event.third);
                break;
            case GEAR:
                onGear(event.first);
                break;
            case VEHICLE_STATE:
                if (event.first == RSM_LIGHT_SW_REASON) onLightSwReason(event.second);
                break;
            default:
                break;
        }
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
        if (!forceInitCompleted) {
            forceInitCarSignalEpoch = epoch;
            timerHandler.postDelayed(forceInitRunnable, FORCE_INIT_MS);
        }
    }

    private void invalidateCarSignalOnMain(long closingEpoch) {
        if (readyCarSignalEpoch != closingEpoch) return;
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

    private void requestSensorLevel(long epoch) {
        Handler io = carSignalIoHandler;
        if (destroyed || io == null || readyCarSignalEpoch != epoch) return;
        io.post(() -> {
            if (!destroyed && carSignalConnected && carSignalEpoch == epoch) {
                requestSensorLevelOnIo(null);
            }
        });
    }

    private void requestSensorLevelForApply(long epoch, SensorApplyMode mode, String reason) {
        requestSensorLevelForApply(epoch, mode, reason, false);
    }

    private void requestSensorLevelForApply(long epoch, SensorApplyMode mode, String reason,
                                            boolean cancelOnManualAuto) {
        if (destroyed || readyCarSignalEpoch != epoch || activeCarSignalEpoch != epoch) return;
        SensorApplyRequest existing = pendingMainSensorApply;
        final SensorApplyRequest request;
        if (existing != null && existing.epoch == epoch
                && existing.mode.priority >= mode.priority) {
            request = existing;
        } else {
            request = new SensorApplyRequest(epoch, ++nextSensorApplyGeneration, mode, reason,
                    cancelOnManualAuto);
            pendingMainSensorApply = request;
        }
        Handler io = carSignalIoHandler;
        if (io != null) {
            io.post(() -> requestSensorLevelOnIo(request));
        }
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

        SensorApplyRequest(long epoch, long generation, SensorApplyMode mode, String reason,
                           boolean cancelOnManualAuto) {
            this.epoch = epoch;
            this.generation = generation;
            this.mode = mode;
            this.reason = reason;
            this.cancelOnManualAuto = cancelOnManualAuto;
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
            carSignalCallbackBinder = new CarSignalCallbackBinder(epoch, service);
            activeCarSignalCallback = carSignalCallbackBinder;
            activeCarSignalEpoch = epoch;
            Log.i(TAG, "CarSignalService connected, alive=" + service.isBinderAlive());
            timerHandler.post(() -> markCarSignalReadyOnMain(epoch));
            requestSensorLevelOnIo(null);
            startRegisterCallbackOnIo();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!isCurrent()) return;
            OldCarSignalSession old = invalidateCarSignalRemoteOnIo();
            if (old.registered) scheduleUnregister(old.remote, old.callback);
            Log.w(TAG, "CarSignalService disconnected — waiting for automatic reconnect");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (isCurrent()) restartCarSignalBindingOnIo("binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (isCurrent()) restartCarSignalBindingOnIo("null binding");
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

    private void requestCarSignalMaintenance() {
        if (destroyed || !carSignalMaintenancePosted.compareAndSet(false, true)) return;
        Handler io = carSignalIoHandler;
        if (io == null || !io.post(() -> {
            try {
                if (destroyed) return;
                ensureBound();
                startRegisterCallbackOnIo();
            } finally {
                carSignalMaintenancePosted.set(false);
            }
        })) {
            carSignalMaintenancePosted.set(false);
        }
    }

    private void ensureBound() {
        if (destroyed || carSignalBindingRequested) return;
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

    private void restartCarSignalBindingOnIo(String reason) {
        Log.w(TAG, "CarSignalService " + reason + " — replacing binding");
        releaseCarSignalBindingOnIo(reason);
        scheduleCarSignalRebindOnIo();
    }

    private void scheduleCarSignalRebindOnIo() {
        if (destroyed) return;
        lastBindAttempt = SystemClock.elapsedRealtime();
        carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
        carSignalIoHandler.postDelayed(carSignalRebindRunnable, BIND_RETRY_MS);
    }

    private void releaseCarSignalBindingOnIo(String reason) {
        carSignalIoHandler.removeCallbacks(carSignalRebindRunnable);
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
                boolean success = result == RegistrationResult.SUCCESS;
                if (result == RegistrationResult.NOT_SENT) {
                    releaseRegistrationSlot(request.callback);
                } else if (result == RegistrationResult.AMBIGUOUS) {
                    // transact reached the vendor but the reply failed: cleanup before retrying,
                    // otherwise an uncounted observer could bypass the process-wide slot limit.
                    scheduleUnregister(request.remote, request.callback);
                }
                Handler io = carSignalIoHandler;
                boolean delivered = io != null
                        && io.post(() -> finishRegisterCallbackOnIo(request, success));
                if (!delivered && success) {
                    // The lifecycle lane is already gone: compensate on this isolated worker.
                    scheduleUnregister(request.remote, request.callback);
                }
            });
        } catch (RejectedExecutionException e) {
            runningRegistration = null;
            releaseRegistrationSlot(request.callback);
            if (request.callback == carSignalCallbackBinder) {
                callbackRegistrationInFlight = false;
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

    private void finishRegisterCallbackOnIo(RegisterRequest request, boolean success) {
        if (runningRegistration != request) return;
        runningRegistration = null;
        boolean current = !destroyed && request.remote == carSignalBinder
                && request.callback == carSignalCallbackBinder
                && request.epoch == carSignalEpoch;
        if (!current) {
            if (success) scheduleUnregister(request.remote, request.callback);
        } else {
            callbackRegistrationInFlight = false;
            if (success) callbackRegistered = true;
        }
        startNextRegistrationOnIo();
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
        if (!callback.cleanupScheduled.compareAndSet(false, true)) return;
        try {
            CAR_SIGNAL_CLEANUP_EXECUTOR.execute(() -> {
                if (unregisterCallbackTransaction(remote, callback)) {
                    callback.cleanupScheduled.set(false);
                    releaseRegistrationSlot(callback);
                } else {
                    Log.w(TAG, "unregisterCallback not confirmed; registration gate remains closed");
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "unregisterCallback deferred: cleanup queue saturated");
        }
    }

    private boolean unregisterCallbackTransaction(IBinder remote, IBinder callback) {
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
            sensorQueryRequested = true;
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
            onSensorLevel(run.epoch, run.ingressRevision, level, "poll");
            SensorApplyRequest apply = run.apply;
            if (apply != null && pendingMainSensorApply == apply) {
                applyAccepted = applySensorRequest(
                        apply, level, run.ingressRevision, run.settingsGeneration);
                if (applyAccepted) pendingMainSensorApply = null;
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
                if (pendingIoSettingsRequest == pending) {
                    pendingIoSettingsRequest = null;
                    pendingIoSettingsGeneration = 0L;
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // RestoreMode settings IO. ContentProvider.query is synchronous Binder work, so it has its
    // own process-wide bounded lane and never runs on main or on either CarSignal lane.
    // -------------------------------------------------------------------------

    private void requestSettingsForApply(SensorApplyRequest request) {
        if (!isSettingsRequestCurrentOnMain(request)) return;
        SensorApplyRequest start = settingsRequestGate.offer(request);
        if (start != null) submitSettingsQuery(start);
    }

    private void submitSettingsQuery(SensorApplyRequest request) {
        if (!isSettingsRequestCurrentOnMain(request)) {
            dropSettingsQueryOnMain(request);
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
            scheduleSettingsRetry();
        }
    }

    private boolean isSettingsRequestCurrentOnMain(SensorApplyRequest request) {
        return !destroyed && pendingMainSensorApply == request
                && request.epoch == readyCarSignalEpoch
                && request.epoch == activeCarSignalEpoch;
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
            CarSignalCallbackBinder callback = activeCarSignalCallback;
            long liveRevisionFence = callback != null && callback.epoch == request.epoch
                    ? callback.ingressRevision.get() : Long.MAX_VALUE;
            long settingsGeneration = ++nextSettingsSnapshotGeneration;
            pendingSettingsSnapshot = new SettingsSnapshot(
                    request, thresholds, settingsGeneration, liveRevisionFence);
            // Never apply a threshold result to the sensor value captured before the blocking
            // provider call. Request a fresh, epoch/revision-protected TX36 instead.
            Handler io = carSignalIoHandler;
            if (io != null) io.post(() -> {
                if (destroyed || request.epoch != carSignalEpoch
                        || pendingIoSensorApply != request) {
                    return;
                }
                pendingIoSettingsRequest = request;
                pendingIoSettingsGeneration = settingsGeneration;
                requestSensorLevelOnIo(request);
            });
        }
        if (completion.next != null) submitSettingsQuery(completion.next);
    }

    private void scheduleSettingsRetry() {
        if (destroyed) return;
        timerHandler.removeCallbacks(settingsRetryRunnable);
        timerHandler.postDelayed(settingsRetryRunnable, BIND_RETRY_MS);
    }

    private final Runnable settingsRetryRunnable = () -> {
        if (destroyed) return;
        SensorApplyRequest retry = settingsRequestGate.retry();
        if (retry != null) submitSettingsQuery(retry);
    };

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

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.i(TAG, "onCreate() — LightSensorService (event-driven + safety-poll)");
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
        HeadlightCanTransport.initialize(this);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Автосвет")
                .setContentText("Управление фарами активно")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
        startForeground(2, notification);

        IntentFilter reqFilter = new IntentFilter("ru.big.town.anative.REQUEST_LUX_UPDATE");
        registerReceiver(requestReceiver, reqFilter, RECEIVER_EXPORTED);

        requestCarSignalMaintenance();
        canBusSubscription = CanBusEventHub.get(this).subscribe(
                CanBusEventRouter.INTEREST_LIGHT_STATUS
                        | CanBusEventRouter.INTEREST_GEAR
                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                new int[]{RSM_LIGHT_SW_REASON}, timerHandler, this::onCanBusEvent);
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
        Log.i(TAG, "onDestroy() — headlightsOn=" + headlightsOn);
        destroyed = true;
        settingsRequestGate.close();
        LatestIntDelivery sensorDelivery = sensorCallbackDelivery;
        sensorCallbackDelivery = null;
        if (sensorDelivery != null) sensorDelivery.close();
        CanBusEventHub.Subscription subscription = canBusSubscription;
        canBusSubscription = null;
        if (subscription != null) subscription.close();
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        timerHandler.removeCallbacks(safetyRunnable);
        timerHandler.removeCallbacks(forceInitRunnable);
        timerHandler.removeCallbacks(sensorDebounceRunnable);
        timerHandler.removeCallbacks(canbusReassertRunnable);
        timerHandler.removeCallbacks(driveFallbackRunnable);
        timerHandler.removeCallbacks(settingsRetryRunnable);
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
                    pendingMainSensorApply = null;
                    acknowledgeSensorApplyFromCallback(epoch, apply.generation);
                }
            }
        }
    };

    // Один раз через FORCE_INIT_MS после готовности подписок принудительно выставляем таргет
    // (уличный если известен, иначе фолбэк на салонный) — гарантия установки на холодном старте.
    private final Runnable forceInitRunnable = new Runnable() {
        @Override
        public void run() {
            long epoch = forceInitCarSignalEpoch;
            if (epoch == 0L || epoch != readyCarSignalEpoch
                    || epoch != activeCarSignalEpoch) {
                return;
            }
            forceInitCarSignalEpoch = 0L;
            if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
                Log.i(TAG, "force-init: OEM Auto выбран с руля — инициализация отменена");
                forceInitCompleted = true;
                return;
            }
            if (reasonToDesired(lastReason) != null) {
                forceInitCompleted = applyTargetWithSensorLevel(
                        "force-init", -1, true);
            } else {
                requestSensorLevelForApply(
                        epoch, SensorApplyMode.FORCE, "force-init", true);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Safety-poll: страховка (колбэк остаётся основным триггером)
    // -------------------------------------------------------------------------

    private final Runnable safetyRunnable = new Runnable() {
        @Override
        public void run() {
            requestCarSignalMaintenance();
            long epoch = readyCarSignalEpoch;
            Boolean outdoor = reasonToDesired(lastReason);
            if (!everSent && outdoor != null) {
                applyTargetWithSensorLevel("poll-retry", -1);
            }
            if (epoch != 0L && epoch == activeCarSignalEpoch) {
                if (!everSent && outdoor == null) {
                    requestSensorLevelForApply(
                            epoch, SensorApplyMode.IF_UNSENT, "poll-retry");
                } else {
                    requestSensorLevel(epoch);
                }
            }
            timerHandler.postDelayed(this, SAFETY_POLL_MS);
        }
    };

    /**
     * Показание салонного датчика (из колбэка/поллинга) — только для индикации в UI.
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
        boolean fulfilled = applyTargetWithSensorLevel(
                request.reason, level, request.mode == SensorApplyMode.FORCE, thresholds);
        if (fulfilled && request.mode == SensorApplyMode.FORCE) {
            forceInitCompleted = true;
        }
        return fulfilled;
    }

    /**
     * Выставить целевой режим фар: приоритет — уличный датчик (последний lightSWReason);
     * если данных улицы нет — фолбэк на салонный уровень по порогам.
     */
    private boolean applyTargetWithSensorLevel(String src, int sensorLevel) {
        return applyTargetWithSensorLevel(src, sensorLevel, false, null);
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget) {
        return applyTargetWithSensorLevel(src, sensorLevel, retainCurrentTarget, null);
    }

    private boolean applyTargetWithSensorLevel(String src, int sensorLevel,
                                               boolean retainCurrentTarget,
                                               LightThresholds thresholds) {
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
        return commit(desired, s2);
    }

    private boolean commit(boolean targetOn, String reason) {
        Log.i(TAG, "★ commit(" + (targetOn ? "ближний" : "выкл") + ") — " + reason);
        final long automaticToken = MANUAL_AUTO_GATE.beginAutomaticDecision();
        if (automaticToken == ManualAutoGate.INVALID_AUTOMATIC_TOKEN) {
            Log.i(TAG, "auto-light decision suppressed by queued manual command");
            return false;
        }
        final long sequence = ++commitSequence;
        headlightsOn      = targetOn;
        everSent          = true;
        lastCommitElapsed = SystemClock.elapsedRealtime();
        ApplyEngine.postWakeAction("auto light " + (targetOn ? "low" : "off"),
                () -> {
                    if (!MANUAL_AUTO_GATE.isAutomaticActionCurrent(automaticToken)) {
                        Log.i(TAG, "auto-light action superseded by newer manual intent");
                        // The manual command may still fail. Keep this commit retryable instead of
                        // recording a send which never happened.
                        return false;
                    }
                    return MainActivity.setHeadlights(this, targetOn);
                },
                result -> {
                    if (result != ApplyEngine.WakeActionResult.SUCCESS) {
                        invalidateCommit(sequence);
                    }
                });
        return true;
    }

    private void invalidateCommit(long sequence) {
        if (destroyed) return;
        timerHandler.post(() -> {
            if (!destroyed && commitSequence == sequence) {
                everSent = false;
                Log.w(TAG, "auto-light commit was cancelled/failed; safety poll will retry");
            }
        });
    }

    /**
     * Уличный датчик (BCM_RSM_lightSWReason, лобовой RSM) — основной источник автосвета.
     * 0 Day → выкл; 2 Dark / 3 Tunnel / 4 Darkstart → ближний; 1 Others — не меняем.
     */
    private void onLightSwReason(int reason) {
        lastReason = reason; // запоминаем последнее известное состояние улицы (для анти-Auto по Drive)
        Boolean desired = reasonToDesired(reason);
        Log.i(TAG, "RSM lightSWReason=" + reason + " → "
                + (desired == null ? "без изменений" : (desired ? "ближний" : "выкл")));
        if (desired == null) return;
        if (!everSent || desired != headlightsOn) commit(desired, "ext-sensor reason=" + reason);
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
            if (MANUAL_AUTO_GATE.blocksAntiAuto()) {
                Log.i(TAG, "drive+5s: OEM Auto выбран с руля — anti-Auto пропущен");
                return;
            }
            if (reasonToDesired(lastReason) != null) {
                applyTargetWithSensorLevel("drive+5s (анти-Auto)", -1);
                return;
            }
            long epoch = readyCarSignalEpoch;
            if (epoch != 0L && epoch == activeCarSignalEpoch) {
                requestSensorLevelForApply(
                        epoch, SensorApplyMode.FORCE, "drive+5s (анти-Auto)", true);
            }
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
            timerHandler.postDelayed(canbusReassertRunnable, CANBUS_REASSERT_DELAY_MS);
        }
    }

    // Отложенная переустановка ближнего после того как поймали «авто». Перед запуском
    // ещё раз проверяем актуальность (таргет ближний и BCM всё ещё в авто).
    private final Runnable canbusReassertRunnable = new Runnable() {
        @Override
        public void run() {
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
            commit(true, "canbus-reset");
        }
    };

    static ManualAutoGate.Ticket reserveManualHeadlightCommand() {
        return MANUAL_AUTO_GATE.reserveManualCommand();
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
