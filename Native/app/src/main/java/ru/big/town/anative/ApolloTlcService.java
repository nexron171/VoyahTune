package ru.big.town.anative;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Demand-scoped, read-only Apollo diagnostics for the allow-listed OEM Binder ABI. */
public final class ApolloTlcService extends Service {
    private static final String TAG = "$$$ ApolloTlcService $$$";
    private static final String RESTOREMODE_PACKAGE = "ru.big.town.restoremode";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    public static final String ACTION_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.APOLLO_TLC_UPDATE";
    public static final String ACTION_REQUEST_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.REQUEST_APOLLO_TLC_UPDATE";
    public static final String ACTION_RELEASE_APOLLO_TLC_DEMAND =
            "ru.big.town.anative.RELEASE_APOLLO_TLC_DEMAND";

    public static final String EXTRA_CAN_CONNECTED = "canConnected";
    public static final String EXTRA_PROFILE_SUPPORTED = "profileSupported";
    public static final String EXTRA_DIRECT_TLC_MODE = "directTlcMode";
    public static final String EXTRA_GEAR = "gear";
    public static final String EXTRA_PLC_SWITCH = "plcSwitch";
    public static final String EXTRA_GLA_SWITCH = "glaSwitch";
    public static final String EXTRA_GLA_LIGHT_CHANGE_SWITCH = "glaLightChangeSwitch";
    public static final String EXTRA_TSR_SWITCH = "tsrSwitch";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_DEMAND_SESSION = "apolloDemandSession";
    public static final String EXTRA_DEMAND_OWNER = "apolloDemandOwner";

    private static final String ACTION_INTERNAL_QUERY =
            "ru.big.town.anative.internal.APOLLO_TLC_QUERY";
    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_PERMISSION =
            "com.qinggan.permission.WRITE_CANBUS";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final int TX_GET_GEAR_STATUS = 6;
    private static final int TX_GET_VEHICLE_STATE = 57;

    private static final long BIND_RETRY_MS = 5_000L;
    private static final long BIND_RETRY_MAX_MS = 60_000L;
    private static final long BIND_CONNECT_TIMEOUT_MS = 20_000L;
    private static final long IDLE_UNBIND_GRACE_MS = 5_000L;
    private static final long VENDOR_BINDER_CALL_TIMEOUT_MS = 15_000L;
    private static final long SCHEMA_THREAD_IDLE_TIMEOUT_SECONDS = 30L;

    /**
     * Owns the complete mutable Apollo/CanBus state machine. Service lifecycle, UI requests
     * and ServiceConnection callbacks only enqueue work here; no synchronous
     * CanBus transaction is allowed to run on the process main thread.
     */
    private HandlerThread canBusWorkerThread;
    private volatile Handler handler;
    private final ApolloCanBusDemandGate canBusDemandGate =
            new ApolloCanBusDemandGate();
    private final Object demandOwnerLock = new Object();
    private DemandOwnerLink demandOwnerLink;
    /**
     * APK/ClassLoader verification is latest-only. A reconnect flap may leave one task running and
     * one newer task queued, but can never grow the default unbounded executor queue. The thread is
     * also allowed to disappear while Apollo has no schema work.
     */
    private final ThreadPoolExecutor schemaExecutor = createSchemaExecutor();
    private final Handler processWatchdogHandler = new Handler(Looper.getMainLooper());
    private final Object vendorBinderWatchdogLock = new Object();
    private int vendorBinderWatchdogGeneration;
    private Runnable vendorBinderWatchdog;

    private IBinder canBusBinder;
    private ServiceConnection canBusConnection;
    private boolean canBusBindingRequested;
    private boolean canBusConnected;
    private volatile boolean destroyed;
    private boolean requestReceiverRegistered;
    private volatile boolean canBusVerificationPending;
    private volatile int canBusVerificationGeneration;
    private volatile IBinder pendingCanBusBinder;
    private int bindEpoch;
    private int activeBindEpoch;
    private int rebindAttempt;
    private Runnable bindConnectWatchdog;
    private Runnable idleUnbindRunnable;

    /** Exact death registration belonging to one accepted UI demand tuple. */
    private final class DemandOwnerLink implements IBinder.DeathRecipient {
        final long sessionToken;
        final IBinder owner;

        DemandOwnerLink(long sessionToken, IBinder owner) {
            this.sessionToken = sessionToken;
            this.owner = owner;
        }

        @Override
        public void binderDied() {
            postWorker(() -> handleDemandOwnerDeath(this));
        }
    }

    private boolean schemaCheckComplete;
    private boolean schemaCheckPending;
    private boolean canBusSchemaMatches;
    private boolean runtimeProfileValid = true;
    private String canBusSchemaError = "profile_check_pending";
    private final EnumMap<ApolloTlcPolicy.Signal, Integer> runtimeSignalOrdinals =
            new EnumMap<>(ApolloTlcPolicy.Signal.class);
    private String lastError = ApolloTlcPolicy.ERROR_NONE;

    private int gear = ApolloTlcPolicy.UNKNOWN;
    private int plcSwitch = ApolloTlcPolicy.UNKNOWN;
    private int glaSwitch = ApolloTlcPolicy.UNKNOWN;
    private int glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
    private int tsrSwitch = ApolloTlcPolicy.UNKNOWN;

    private final Runnable rebindRunnable = () -> runWorkerSafely(
            "scheduled rebind", () -> revalidateCanBusAndBind("scheduled rebind"));

    private static ThreadPoolExecutor createSchemaExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1,
                SCHEMA_THREAD_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "ApolloTlcSchema");
                    thread.setDaemon(true);
                    return thread;
                },
                (latest, target) -> {
                    if (target.isShutdown()) {
                        throw new RejectedExecutionException("Apollo schema executor is shut down");
                    }
                    target.getQueue().poll();
                    if (!target.getQueue().offer(latest)) {
                        throw new RejectedExecutionException("Apollo schema latest-only queue full");
                    }
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long sessionToken = intent.getLongExtra(EXTRA_DEMAND_SESSION, 0L);
            IBinder owner = binderExtra(intent, EXTRA_DEMAND_OWNER);
            if (ACTION_REQUEST_APOLLO_TLC_UPDATE.equals(intent.getAction())) {
                enqueueQuery(sessionToken, owner);
            } else if (ACTION_RELEASE_APOLLO_TLC_DEMAND.equals(intent.getAction())) {
                postWorker(() -> releaseClientDemand(sessionToken, owner, "lifecycle release"));
            }
        }
    };

    private ServiceConnection createCanBusConnection(final int connectionEpoch) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                ServiceConnection source = this;
                postWorker(() -> {
                    if (!isCurrentConnection(connectionEpoch, source)) {
                        unbindStaleConnection(source, "stale connected");
                        return;
                    }
                    cancelBindConnectWatchdog();
                    handler.removeCallbacks(rebindRunnable);
                    canBusBindingRequested = true;
                    if (!hasClientDemand()) {
                        releaseCanBusTransportWithoutDemand("connected after UI release");
                        return;
                    }
                    verifyConnectedCanBus(service);
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                ServiceConnection source = this;
                postWorker(() -> {
                    if (!isCurrentConnection(connectionEpoch, source)) return;
                    if (!hasClientDemand()) {
                        releaseCanBusTransportWithoutDemand("disconnected while idle");
                        return;
                    }
                    invalidateCanBusIdentity("can_disconnected");
                    scheduleCanBusRebind();
                });
            }

            @Override
            public void onBindingDied(ComponentName name) {
                ServiceConnection source = this;
                postWorker(() -> {
                    if (isCurrentConnection(connectionEpoch, source)) {
                        restartCanBusBinding("binding_died");
                    }
                });
            }

            @Override
            public void onNullBinding(ComponentName name) {
                ServiceConnection source = this;
                postWorker(() -> {
                    if (isCurrentConnection(connectionEpoch, source)) {
                        restartCanBusBinding("null_binding");
                    }
                });
            }
        };
    }

    private boolean isCurrentConnection(int connectionEpoch, ServiceConnection candidate) {
        return ApolloTlcPolicy.connectionEventCurrent(
                destroyed, activeBindEpoch, connectionEpoch,
                candidate == canBusConnection);
    }

    private void unbindStaleConnection(ServiceConnection connection, String reason) {
        try {
            unbindService(connection);
        } catch (RuntimeException e) {
            Log.w(TAG, reason + ": stale unbind failed", e);
        }
    }

    /** Posts immediate work to the serial state-machine thread. */
    private boolean postWorker(Runnable action) {
        Handler target = handler;
        if (destroyed || target == null) return false;
        return target.post(() -> runWorkerSafely("queued work", action));
    }

    /** Keeps one unexpected task failure from terminating the authoritative HandlerThread. */
    private void runWorkerSafely(String source, Runnable action) {
        if (destroyed) return;
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.e(TAG, source + " failed", e);
            if (destroyed) return;
            failRuntimeProfileClosed("worker_task_failed");
            try {
                publishState();
            } catch (RuntimeException publishError) {
                Log.e(TAG, "Cannot publish worker failure", publishError);
            }
        }
    }

    /**
     * Acquires every versioned UI session even when its expensive CAN refresh is coalesced.
     * One trailing refresh guarantees that a newer visible session receives a snapshot after an
     * older synchronous Binder read completes.
     */
    private void enqueueQuery(long sessionToken, IBinder owner) {
        if (destroyed) return;
        if (sessionToken <= 0L || owner == null) {
            Log.w(TAG, "Ignoring Apollo query without a valid demand owner/session");
            return;
        }
        postWorker(() -> {
            int acquired = acquireClientDemand(sessionToken, owner);
            if (acquired == ApolloCanBusDemandGate.ACQUIRE_REJECTED) return;
            if (!canBusDemandGate.beginQuery(sessionToken, owner)) return;
            postQueryWork(sessionToken, owner);
        });
    }

    private void postQueryWork(long sessionToken, IBinder owner) {
        if (!postWorker(() -> {
            try {
                if (canBusDemandGate.isActive(sessionToken, owner)) {
                    handleQuery();
                }
            } finally {
                long trailingSession = canBusDemandGate.finishQuery(sessionToken);
                if (trailingSession != ApolloCanBusDemandGate.NO_QUERY_SESSION) {
                    IBinder trailingOwner = activeDemandOwner(trailingSession);
                    if (trailingOwner != null) {
                        postQueryWork(trailingSession, trailingOwner);
                    } else {
                        canBusDemandGate.abandonQuery(trailingSession);
                    }
                }
            }
        })) {
            canBusDemandGate.abandonQuery(sessionToken);
        }
    }

    /** A real UI query opens one idempotent, monotonically versioned CanBus demand session. */
    private int acquireClientDemand(long sessionToken, IBinder owner) {
        boolean existing = false;
        synchronized (demandOwnerLock) {
            DemandOwnerLink current = demandOwnerLink;
            if (!destroyed && current != null && current.sessionToken == sessionToken
                    && current.owner == owner
                    && canBusDemandGate.isActive(sessionToken, owner)) {
                existing = true;
            }
        }
        if (existing) {
            cancelIdleUnbind();
            return ApolloCanBusDemandGate.ACQUIRE_EXISTING;
        }

        DemandOwnerLink candidate = new DemandOwnerLink(sessionToken, owner);
        try {
            owner.linkToDeath(candidate, 0);
        } catch (RemoteException | RuntimeException e) {
            unlinkDemandOwner(candidate);
            Log.w(TAG, "Apollo demand owner unavailable; session=" + sessionToken, e);
            releaseClientDemand(sessionToken, owner, "owner unavailable");
            return ApolloCanBusDemandGate.ACQUIRE_REJECTED;
        }

        int result = ApolloCanBusDemandGate.ACQUIRE_REJECTED;
        DemandOwnerLink previous = null;
        boolean adopted = false;
        synchronized (demandOwnerLock) {
            if (!destroyed) {
                result = canBusDemandGate.acquire(sessionToken, owner);
                if (result == ApolloCanBusDemandGate.ACQUIRE_NEW) {
                    previous = demandOwnerLink;
                    demandOwnerLink = candidate;
                    adopted = true;
                } else if (result == ApolloCanBusDemandGate.ACQUIRE_EXISTING) {
                    DemandOwnerLink current = demandOwnerLink;
                    if (current == null || current.sessionToken != sessionToken
                            || current.owner != owner) {
                        previous = current;
                        demandOwnerLink = candidate;
                        adopted = true;
                    }
                }
            }
        }
        if (!adopted) unlinkDemandOwner(candidate);
        if (previous != null) unlinkDemandOwner(previous);
        if (result == ApolloCanBusDemandGate.ACQUIRE_REJECTED) return result;
        cancelIdleUnbind();
        if (result == ApolloCanBusDemandGate.ACQUIRE_NEW) {
            // A new UI session must not inherit a minute-long backoff from an older one.
            rebindAttempt = 0;
            Log.i(TAG, "Apollo UI demand acquired; session=" + sessionToken);
        }
        return result;
    }

    /** Called by the permission-gated RestoreMode lifecycle broadcast. */
    private void releaseClientDemand(long sessionToken, IBinder owner, String reason) {
        if (sessionToken <= 0L || owner == null) return;
        DemandOwnerLink released;
        synchronized (demandOwnerLock) {
            if (destroyed || !canBusDemandGate.release(sessionToken, owner)) return;
            released = demandOwnerLink;
            demandOwnerLink = null;
        }
        if (released != null) unlinkDemandOwner(released);
        handler.removeCallbacks(rebindRunnable);
        Log.i(TAG, "Apollo UI demand released (" + reason + "); session=" + sessionToken
                + " idle grace=" + IDLE_UNBIND_GRACE_MS + "ms");
        maybeScheduleIdleUnbind();
    }

    private void handleDemandOwnerDeath(DemandOwnerLink dead) {
        DemandOwnerLink released;
        synchronized (demandOwnerLock) {
            if (destroyed || demandOwnerLink != dead
                    || !canBusDemandGate.ownerDied(dead.sessionToken, dead.owner)) return;
            released = demandOwnerLink;
            demandOwnerLink = null;
        }
        unlinkDemandOwner(released);
        handler.removeCallbacks(rebindRunnable);
        Log.i(TAG, "Apollo UI process died; session=" + dead.sessionToken
                + " idle grace=" + IDLE_UNBIND_GRACE_MS + "ms");
        maybeScheduleIdleUnbind();
    }

    private IBinder activeDemandOwner(long sessionToken) {
        synchronized (demandOwnerLock) {
            DemandOwnerLink current = demandOwnerLink;
            if (destroyed || current == null || current.sessionToken != sessionToken
                    || !canBusDemandGate.isActive(sessionToken, current.owner)) return null;
            return current.owner;
        }
    }

    private static void unlinkDemandOwner(DemandOwnerLink link) {
        if (link == null) return;
        try {
            link.owner.unlinkToDeath(link, 0);
        } catch (RuntimeException ignored) {
            // Already dead/unlinked is an idempotent terminal state.
        }
    }

    private boolean hasClientDemand() {
        synchronized (demandOwnerLock) {
            DemandOwnerLink current = demandOwnerLink;
            return !destroyed && current != null
                    && canBusDemandGate.isActive(current.sessionToken, current.owner);
        }
    }

    private void cancelIdleUnbind() {
        Runnable idle = idleUnbindRunnable;
        idleUnbindRunnable = null;
        Handler target = handler;
        if (idle != null && target != null) target.removeCallbacks(idle);
    }

    /** Keeps a short grace for Activity recreation without maintaining a recurring poll. */
    private void maybeScheduleIdleUnbind() {
        if (destroyed) return;
        int generation = canBusDemandGate.armIdleRelease(false);
        if (generation == ApolloCanBusDemandGate.REJECTED_GENERATION) return;
        cancelIdleUnbind();
        Runnable idle = () -> runWorkerSafely("idle CanBus release", () -> {
            if (!canBusDemandGate.isIdleReleaseCurrent(generation, false)) return;
            idleUnbindRunnable = null;
            releaseCanBusTransportWithoutDemand("Apollo UI idle");
        });
        idleUnbindRunnable = idle;
        handler.postDelayed(idle, IDLE_UNBIND_GRACE_MS);
    }

    /** Intentional release: fence late callbacks without turning idle into a profile failure. */
    private void releaseCanBusTransportWithoutDemand(String reason) {
        boolean hadTransport = canBusBindingRequested || canBusConnected || canBusBinder != null;
        canBusDemandGate.invalidateIdleRelease();
        cancelIdleUnbind();
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        releaseCanBusBinding(reason);
        invalidateCanSnapshot();
        if (hadTransport) Log.i(TAG, "CanBus transport released: " + reason);
        publishState();
    }

    public static void requestQuery(Context context, long sessionToken, IBinder owner) {
        start(context, ACTION_INTERNAL_QUERY, sessionToken, owner);
    }

    public static void ensureStarted(Context context) {
        start(context, null, 0L, null);
    }

    private static void start(Context context, String action,
                              long sessionToken, IBinder owner) {
        Intent intent = new Intent(context, ApolloTlcService.class);
        intent.setAction(action);
        if (sessionToken > 0L) intent.putExtra(EXTRA_DEMAND_SESSION, sessionToken);
        if (owner != null) {
            Bundle ownerExtra = new Bundle();
            ownerExtra.putBinder(EXTRA_DEMAND_OWNER, owner);
            intent.putExtras(ownerExtra);
        }
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start read-only Apollo diagnostics for " + action, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        canBusWorkerThread = new HandlerThread("ApolloTlcCanBus");
        canBusWorkerThread.start();
        handler = new Handler(canBusWorkerThread.getLooper());
        boolean receiverFailed = false;
        try {
            IntentFilter requestFilter =
                    new IntentFilter(ACTION_REQUEST_APOLLO_TLC_UPDATE);
            requestFilter.addAction(ACTION_RELEASE_APOLLO_TLC_DEMAND);
            registerReceiver(requestReceiver, requestFilter,
                    BIND_PERMISSION, null, RECEIVER_EXPORTED);
            requestReceiverRegistered = true;
        } catch (RuntimeException e) {
            receiverFailed = true;
            Log.e(TAG, "Cannot register request receiver", e);
        }
        final boolean requestReceiverFailed = receiverFailed;
        postWorker(() -> {
            if (requestReceiverFailed) {
                lastError = "request_receiver_failed";
            }
            // Keep the signature-protected fallback receiver available after boot, but do not run
            // PackageManager/PathClassLoader work until a real Apollo UI query owns demand.
            if (BuildConfig.HAS_DIRECT_APOLLO && !hasCanBusPermission()) {
                failCanBusPermissionClosed();
            }
            publishState();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final long sessionToken = intent == null
                ? 0L : intent.getLongExtra(EXTRA_DEMAND_SESSION, 0L);
        final IBinder owner = binderExtra(intent, EXTRA_DEMAND_OWNER);
        if (ACTION_INTERNAL_QUERY.equals(action)) {
            enqueueQuery(sessionToken, owner);
        } else if (action != null) {
            Log.w(TAG, "Ignoring unknown read-only Apollo action: " + action);
        }
        return BuildConfig.HAS_DIRECT_APOLLO ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        DemandOwnerLink releasedOwner;
        synchronized (demandOwnerLock) {
            canBusDemandGate.close();
            releasedOwner = demandOwnerLink;
            demandOwnerLink = null;
        }
        unlinkDemandOwner(releasedOwner);
        if (requestReceiverRegistered) {
            try {
                unregisterReceiver(requestReceiver);
            } catch (RuntimeException ignored) {
            }
            requestReceiverRegistered = false;
        }
        schemaExecutor.shutdownNow();
        Handler worker = handler;
        HandlerThread thread = canBusWorkerThread;
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            boolean cleanupPosted = worker.postAtFrontOfQueue(() -> {
                // A synchronous vendor transaction may have returned after onDestroy began and
                // queued delayed work. Invalidate every generation and clear the queue again here.
                ++canBusVerificationGeneration;
                worker.removeCallbacksAndMessages(null);
                releaseCanBusBinding("destroy");
                if (thread != null) thread.quitSafely();
            });
            if (!cleanupPosted && thread != null) thread.quitSafely();
        } else if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    private static IBinder binderExtra(Intent intent, String key) {
        Bundle extras = intent == null ? null : intent.getExtras();
        return extras == null ? null : extras.getBinder(key);
    }

    private void handleQuery() {
        if (!BuildConfig.HAS_DIRECT_APOLLO) {
            invalidateCanSnapshot();
            publishState();
            return;
        }
        if (!hasCanBusPermission()) {
            failCanBusPermissionClosed();
            publishState();
            return;
        }
        if (!schemaCheckComplete) {
            startSchemaCheck();
            publishState();
            return;
        }
        lastError = ApolloTlcPolicy.ERROR_NONE;
        if (!isBinderProfilePinned()) {
            invalidateCanSnapshot();
            if (schemaCheckComplete && !canBusVerificationPending) {
                revalidateCanBusAndBind("query");
            } else {
                publishState();
            }
            return;
        }
        if (!runtimeProfileValid) {
            invalidateCanSnapshot();
            publishState();
            return;
        }
        ensureCanBusBound();
        if (canBusConnected && !refreshFromCan("query")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        } else if (!canBusConnected) {
            invalidateCanSnapshot();
        }
        publishState();
    }

    private boolean refreshFromCan(String reason) {
        if (!runtimeProfileValid || !hasCanBusPermission()
                || !canBusConnected || canBusBinder == null) {
            invalidateCanSnapshot();
            return false;
        }
        try {
            plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
            glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
            glaLightChangeSwitch = getVehicleState(
                    ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH);
            tsrSwitch = getVehicleState(ApolloTlcPolicy.Signal.TSR_SWITCH);
            GearReading reading = getGearStatus();
            if (!reading.valid) {
                failRuntimeProfileClosed("profile_gear_parcel_mismatch");
                Log.e(TAG, "Invalid GearState parcel ordinal=" + reading.ordinal
                        + " value=" + reading.value);
                return false;
            }
            gear = reading.value;
            Log.i(TAG, reason + ": gear=" + gear + " plc=" + plcSwitch
                    + " gla=" + glaSwitch + " glaSound=" + glaLightChangeSwitch
                    + " tsr=" + tsrSwitch);
            return true;
        } catch (RemoteException | RuntimeException e) {
            invalidateCanSnapshot();
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, reason + " failed", e);
            return false;
        }
    }

    /** Permanently closes this service instance after a pinned protocol mismatch. */
    private void failRuntimeProfileClosed(String error) {
        if (!runtimeProfileValid) return;
        runtimeProfileValid = false;
        invalidateCanSnapshot();
        lastError = error;
        releaseCanBusBinding(error);
    }

    /** Closes the read-only Binder gate once when the vendor permission is unavailable. */
    private void failCanBusPermissionClosed() {
        lastError = ApolloTlcPolicy.ERROR_CAN_PERMISSION_MISSING;
        invalidateCanSnapshot();
        releaseCanBusBinding("CanBus permission missing");
    }

    private void invalidateCanSnapshot() {
        gear = ApolloTlcPolicy.UNKNOWN;
        plcSwitch = ApolloTlcPolicy.UNKNOWN;
        glaSwitch = ApolloTlcPolicy.UNKNOWN;
        glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
        tsrSwitch = ApolloTlcPolicy.UNKNOWN;
    }

    private int getVehicleState(ApolloTlcPolicy.Signal signal) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            appendVehicleStateIdentity(data, signal);
            if (!transactCanBus(TX_GET_VEHICLE_STATE, data, reply)) {
                throw new RemoteException("TX57 rejected");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Presence marker plus runtime-resolved VehicleState ordinal and stable id. */
    private void appendVehicleStateIdentity(Parcel data, ApolloTlcPolicy.Signal signal) {
        Integer ordinal = runtimeSignalOrdinals.get(signal);
        if (ordinal == null) {
            throw new IllegalStateException("VehicleState schema not resolved for " + signal);
        }
        data.writeInt(1);
        data.writeInt(ordinal);
        data.writeInt(signal.id);
    }

    private GearReading getGearStatus() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            if (!transactCanBus(TX_GET_GEAR_STATUS, data, reply)) {
                throw new RemoteException("TX6 rejected");
            }
            reply.readException();
            if (reply.readInt() == 0) {
                return new GearReading(ApolloTlcPolicy.UNKNOWN,
                        ApolloTlcPolicy.UNKNOWN, false);
            }
            int ordinal = reply.readInt();
            int value = reply.readInt();
            boolean valid = (ordinal >= 0 && ordinal <= 4 && value == ordinal)
                    || (ordinal == 5 && value == -1);
            return new GearReading(ordinal, value, valid);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static final class GearReading {
        final int ordinal;
        final int value;
        final boolean valid;

        GearReading(int ordinal, int value, boolean valid) {
            this.ordinal = ordinal;
            this.value = value;
            this.valid = valid;
        }
    }

    /** Performs one synchronous transaction, always from the serial worker thread. */
    private boolean transactCanBus(int transactionCode, Parcel data, Parcel reply)
            throws RemoteException {
        if (destroyed) {
            throw new RemoteException("Apollo service destroyed");
        }
        IBinder binder = canBusBinder;
        if (binder == null) throw new RemoteException("CanBus binder unavailable");
        int watchdogGeneration = armVendorBinderWatchdog("transact " + transactionCode);
        try {
            return binder.transact(transactionCode, data, reply, 0);
        } finally {
            cancelVendorBinderWatchdog(watchdogGeneration);
        }
    }

    private String readCanBusDescriptor(IBinder service) throws RemoteException {
        int watchdogGeneration = armVendorBinderWatchdog("getInterfaceDescriptor");
        try {
            return service.getInterfaceDescriptor();
        } finally {
            cancelVendorBinderWatchdog(watchdogGeneration);
        }
    }

    /**
     * A kernel Binder call cannot be interrupted from Java. Apollo therefore lives in the private
     * {@code :apollo} process and a deadline kills only that process if the vendor service never
     * replies. The timer exists solely for one in-flight call; it is not a recurring watchdog.
     */
    private int armVendorBinderWatchdog(String operation) {
        final int generation;
        final Runnable watchdog;
        synchronized (vendorBinderWatchdogLock) {
            generation = ++vendorBinderWatchdogGeneration;
            watchdog = () -> {
                synchronized (vendorBinderWatchdogLock) {
                    if (generation != vendorBinderWatchdogGeneration) {
                        return;
                    }
                    vendorBinderWatchdog = null;
                }
                Log.wtf(TAG, "Vendor Binder call timed out: " + operation
                        + "; terminating private Apollo process");
                Process.killProcess(Process.myPid());
            };
            vendorBinderWatchdog = watchdog;
        }
        processWatchdogHandler.postDelayed(watchdog, VENDOR_BINDER_CALL_TIMEOUT_MS);
        return generation;
    }

    private void cancelVendorBinderWatchdog(int generation) {
        Runnable watchdog = null;
        synchronized (vendorBinderWatchdogLock) {
            if (generation == vendorBinderWatchdogGeneration) {
                ++vendorBinderWatchdogGeneration;
                watchdog = vendorBinderWatchdog;
                vendorBinderWatchdog = null;
            }
        }
        if (watchdog != null) processWatchdogHandler.removeCallbacks(watchdog);
    }

    /** Re-resolves the installed VehicleState table before the first Binder transaction. */
    private void verifyConnectedCanBus(IBinder candidate) {
        if (!hasClientDemand()) {
            releaseCanBusTransportWithoutDemand("verification started after UI release");
            return;
        }
        if (destroyed || !BuildConfig.HAS_DIRECT_APOLLO || !schemaCheckComplete) {
            rejectCanBusVerification("profile_canbus_schema_unavailable");
            return;
        }
        final int generation = beginCanBusVerification(candidate);
        if (!submitSchemaTask("connected CanBus verification", () -> {
            // A newer disconnect/reconnect may have superseded this task while it was queued.
            // Drop it before PackageManager/PathClassLoader work, not only after resolution.
            if (!verificationResultCurrent(generation, candidate)) return;
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            postWorker(() -> {
                if (!verificationResultCurrent(generation, candidate)) return;
                if (!hasClientDemand()) {
                    releaseCanBusTransportWithoutDemand(
                            "verification completed after UI release");
                    return;
                }
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                applyVehicleStateSchema(result);
                if (!result.matches) {
                    rejectCanBusVerification(result.error);
                    return;
                }
                attachVerifiedCanBus(candidate);
            });
        }) && !destroyed && verificationResultCurrent(generation, candidate)) {
            rejectCanBusVerification("profile_executor_unavailable");
        }
    }

    private void attachVerifiedCanBus(IBinder service) {
        if (destroyed) return;
        if (!hasClientDemand()) {
            releaseCanBusTransportWithoutDemand("attachment after UI release");
            return;
        }
        if (!hasCanBusPermission()) {
            failCanBusPermissionClosed();
            publishState();
            return;
        }
        try {
            if (!CANBUS_DESCRIPTOR.equals(readCanBusDescriptor(service))) {
                failRuntimeProfileClosed("profile_binder_descriptor_mismatch");
                Log.e(TAG, "Unexpected CanBus Binder descriptor after schema verification");
                releaseCanBusBinding("descriptor mismatch");
                publishState();
                return;
            }
        } catch (RemoteException | RuntimeException e) {
            lastError = "can_descriptor_failed";
            Log.e(TAG, "Cannot verify CanBus Binder descriptor", e);
            restartCanBusBinding("descriptor failed");
            return;
        }
        canBusBinder = service;
        canBusConnected = true;
        lastError = ApolloTlcPolicy.ERROR_NONE;
        Log.i(TAG, "CanBusService connected without global callback subscription");
        rebindAttempt = 0;
        if (!refreshFromCan("connect")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        }
        publishState();
    }

    /** Re-resolves the runtime VehicleState schema, then creates a fresh binding on success. */
    private void revalidateCanBusAndBind(String reason) {
        if (destroyed || !BuildConfig.HAS_DIRECT_APOLLO || !schemaCheckComplete
                || !hasCanBusPermission()
                || canBusVerificationPending || !hasClientDemand()) {
            return;
        }
        // This path is entered only after disconnect/death/rejection. Mark disconnected before
        // releasing the stale binding.
        canBusBinder = null;
        canBusConnected = false;
        releaseCanBusBinding(reason);

        final int generation = beginCanBusVerification(null);
        if (!submitSchemaTask("CanBus revalidation", () -> {
            if (!verificationResultCurrent(generation, null)) return;
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            postWorker(() -> {
                if (!verificationResultCurrent(generation, null)) return;
                if (!hasClientDemand()) {
                    releaseCanBusTransportWithoutDemand(
                            "revalidation completed after UI release");
                    return;
                }
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                applyVehicleStateSchema(result);
                if (!result.matches) {
                    rejectCanBusVerification(result.error);
                    return;
                }
                Log.i(TAG, reason + ": CanBus APK revalidated; binding fresh service");
                ensureCanBusBound();
                publishState();
            });
        }) && !destroyed && verificationResultCurrent(generation, null)) {
            rejectCanBusVerification("profile_executor_unavailable");
        }
    }

    private int beginCanBusVerification(IBinder candidate) {
        int generation = ++canBusVerificationGeneration;
        canBusVerificationPending = true;
        pendingCanBusBinder = candidate;
        canBusSchemaMatches = false;
        canBusSchemaError = "profile_canbus_revalidation_pending";
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        invalidateCanSnapshot();
        publishState();
        return generation;
    }

    private boolean verificationResultCurrent(int generation, IBinder candidate) {
        return canBusVerificationPending
                && ApolloTlcPolicy.verificationResultCurrent(
                BuildConfig.HAS_DIRECT_APOLLO, destroyed,
                canBusVerificationGeneration, generation,
                pendingCanBusBinder == candidate);
    }

    private void rejectCanBusVerification(String error) {
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        canBusSchemaMatches = false;
        canBusSchemaError = error;
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        invalidateCanSnapshot();
        releaseCanBusBinding("CanBus schema verification failed");
        publishState();
    }

    private void ensureCanBusBound() {
        if (destroyed || !hasClientDemand()
                || canBusBindingRequested || !isBinderProfilePinned()) return;
        final int connectionEpoch = ++bindEpoch;
        activeBindEpoch = connectionEpoch;
        ServiceConnection connection = createCanBusConnection(connectionEpoch);
        canBusConnection = connection;
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            canBusBindingRequested = bindService(
                    intent, connection, Context.BIND_AUTO_CREATE);
            if (!canBusBindingRequested) {
                activeBindEpoch = ++bindEpoch;
                canBusConnection = null;
                lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
                scheduleCanBusRebind();
            } else {
                scheduleBindConnectWatchdog(connectionEpoch, connection);
            }
        } catch (RuntimeException e) {
            activeBindEpoch = ++bindEpoch;
            canBusConnection = null;
            canBusBindingRequested = false;
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            Log.e(TAG, "CanBus bind failed", e);
            scheduleCanBusRebind();
        }
    }

    private void scheduleCanBusRebind() {
        if (destroyed || !BuildConfig.HAS_DIRECT_APOLLO || !schemaCheckComplete
                || !hasCanBusPermission() || !hasClientDemand()) return;
        handler.removeCallbacks(rebindRunnable);
        long delayMs = ApolloCanBusDemandGate.reconnectDelayMs(
                rebindAttempt, BIND_RETRY_MS, BIND_RETRY_MAX_MS);
        rebindAttempt = Math.min(rebindAttempt + 1, 5);
        handler.postDelayed(rebindRunnable, delayMs);
        Log.w(TAG, "CanBus rebind scheduled in " + delayMs
                + " ms (attempt " + rebindAttempt + ")");
    }

    private void scheduleBindConnectWatchdog(int connectionEpoch,
                                             ServiceConnection connection) {
        cancelBindConnectWatchdog();
        Runnable watchdog = () -> runWorkerSafely("bind connect watchdog", () -> {
            if (!isCurrentConnection(connectionEpoch, connection)
                    || !canBusBindingRequested || canBusConnected) {
                return;
            }
            bindConnectWatchdog = null;
            if (!hasClientDemand()) {
                releaseCanBusTransportWithoutDemand("bind timeout while idle");
                return;
            }
            Log.e(TAG, "CanBus bind timed out before onServiceConnected");
            restartCanBusBinding("bind_timeout");
        });
        bindConnectWatchdog = watchdog;
        handler.postDelayed(watchdog, BIND_CONNECT_TIMEOUT_MS);
    }

    private void cancelBindConnectWatchdog() {
        Runnable watchdog = bindConnectWatchdog;
        bindConnectWatchdog = null;
        if (watchdog != null) handler.removeCallbacks(watchdog);
    }

    private void restartCanBusBinding(String reason) {
        if (!hasClientDemand()) {
            releaseCanBusTransportWithoutDemand(reason + " while idle");
            return;
        }
        invalidateCanBusIdentity(reason);
        releaseCanBusBinding(reason);
        scheduleCanBusRebind();
    }

    private void invalidateCanBusIdentity(String error) {
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        canBusSchemaMatches = false;
        canBusSchemaError = "profile_canbus_revalidation_pending";
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        invalidateCanSnapshot();
        lastError = error;
        publishState();
    }

    private void releaseCanBusBinding(String reason) {
        handler.removeCallbacks(rebindRunnable);
        cancelBindConnectWatchdog();
        ServiceConnection connection = canBusConnection;
        boolean wasBindingRequested = canBusBindingRequested;
        activeBindEpoch = ++bindEpoch;
        canBusConnection = null;
        canBusBindingRequested = false;
        if (wasBindingRequested && connection != null) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbind failed", e);
            }
        }
        canBusBinder = null;
        canBusConnected = false;
    }

    private void startSchemaCheck() {
        if (destroyed || schemaCheckComplete || schemaCheckPending) return;
        schemaCheckPending = true;
        if (!submitSchemaTask("startup schema verification", () -> {
            VehicleStateSchemaResult schema = resolveVehicleStateSchema();
            postWorker(() -> {
                if (destroyed) return;
                schemaCheckPending = false;
                schemaCheckComplete = true;
                applyVehicleStateSchema(schema);
                if (BuildConfig.HAS_DIRECT_APOLLO && !hasCanBusPermission()) {
                    failCanBusPermissionClosed();
                }
                if (isBinderProfilePinned() && hasClientDemand()) {
                    ensureCanBusBound();
                } else {
                    invalidateCanSnapshot();
                }
                publishState();
            });
        }) && !destroyed) {
            schemaCheckPending = false;
            schemaCheckComplete = true;
            canBusSchemaMatches = false;
            canBusSchemaError = "profile_executor_unavailable";
            failRuntimeProfileClosed("profile_executor_unavailable");
            publishState();
        }
    }

    /** Submits slow APK/schema work without letting a destroy race kill the CAN worker. */
    private boolean submitSchemaTask(String source, Runnable action) {
        if (destroyed) return false;
        try {
            schemaExecutor.execute(action);
            return true;
        } catch (RejectedExecutionException e) {
            if (!destroyed) {
                lastError = "profile_executor_unavailable";
                Log.e(TAG, source + " rejected", e);
            }
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private VehicleStateSchemaResult resolveVehicleStateSchema() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(CANBUS_PACKAGE, 0);
            ClassLoader loader = new PathClassLoader(info.sourceDir, getClassLoader());
            Class enumClass = Class.forName(
                    "com.qinggan.canbus.VehicleState", true, loader);
            if (!enumClass.isEnum()) {
                return VehicleStateSchemaResult.failed("profile_canbus_schema_mismatch");
            }
            Method getValue = enumClass.getMethod("getValue");
            EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals =
                    new EnumMap<>(ApolloTlcPolicy.Signal.class);
            for (ApolloTlcPolicy.Signal signal : ApolloTlcPolicy.Signal.values()) {
                Enum value = Enum.valueOf(enumClass, signal.name());
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer) || ((Integer) stableId) != signal.id) {
                    Log.e(TAG, "VehicleState id mismatch for " + signal.name());
                    return VehicleStateSchemaResult.failed(
                            "profile_canbus_schema_mismatch");
                }
                ordinals.put(signal, value.ordinal());
            }
            return new VehicleStateSchemaResult(
                    true, ApolloTlcPolicy.ERROR_NONE, ordinals);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "CanBusService APK not found");
            return VehicleStateSchemaResult.failed("profile_canbus_apk_not_found");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Cannot resolve installed VehicleState schema", e);
            return VehicleStateSchemaResult.failed("profile_canbus_schema_unavailable");
        }
    }

    private void applyVehicleStateSchema(VehicleStateSchemaResult result) {
        canBusSchemaMatches = result.matches;
        canBusSchemaError = result.error;
        runtimeSignalOrdinals.clear();
        if (result.matches) runtimeSignalOrdinals.putAll(result.ordinals);
    }

    private static final class VehicleStateSchemaResult {
        final boolean matches;
        final String error;
        final EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals;

        VehicleStateSchemaResult(boolean matches, String error,
                                 EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals) {
            this.matches = matches;
            this.error = error;
            this.ordinals = ordinals;
        }

        static VehicleStateSchemaResult failed(String error) {
            return new VehicleStateSchemaResult(
                    false, error, new EnumMap<>(ApolloTlcPolicy.Signal.class));
        }
    }

    /** Read-only diagnostics use only the allow-listed OEM Binder ABI. */
    private boolean isDirectTlcSupported() {
        return BuildConfig.HAS_DIRECT_APOLLO && schemaCheckComplete && canBusSchemaMatches
                && runtimeProfileValid && hasCanBusPermission();
    }

    private boolean isBinderProfilePinned() {
        boolean canBusPermissionGranted = BuildConfig.HAS_DIRECT_APOLLO
                && hasCanBusPermission();
        return ApolloTlcPolicy.binderProfilePinned(
                BuildConfig.HAS_DIRECT_APOLLO, schemaCheckComplete, canBusSchemaMatches,
                canBusPermissionGranted);
    }

    private boolean hasCanBusPermission() {
        return checkSelfPermission(CANBUS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String reportedError(boolean directTlcSupported) {
        if (!BuildConfig.HAS_DIRECT_APOLLO) return ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
        if (!hasCanBusPermission()) {
            return ApolloTlcPolicy.ERROR_CAN_PERMISSION_MISSING;
        }
        if (!schemaCheckComplete) return "profile_check_pending";
        if (!canBusSchemaMatches) return canBusSchemaError;
        if (!runtimeProfileValid) {
            return lastError.isEmpty() ? "profile_runtime_mismatch" : lastError;
        }
        if (!directTlcSupported) return ApolloTlcPolicy.ERROR_PROFILE_UNSUPPORTED;
        if (!canBusConnected) return ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
        if (!lastError.isEmpty()) return lastError;
        return ApolloTlcPolicy.ERROR_NONE;
    }

    private void publishState() {
        if (destroyed) return;
        boolean directTlcSupported = isDirectTlcSupported();
        Intent update = new Intent(ACTION_APOLLO_TLC_UPDATE);
        update.putExtra(EXTRA_CAN_CONNECTED, canBusConnected);
        update.putExtra(EXTRA_PROFILE_SUPPORTED, directTlcSupported);
        update.putExtra(EXTRA_DIRECT_TLC_MODE, directTlcSupported);
        update.putExtra(EXTRA_GEAR, gear);
        update.putExtra(EXTRA_PLC_SWITCH, plcSwitch);
        update.putExtra(EXTRA_GLA_SWITCH, glaSwitch);
        update.putExtra(EXTRA_GLA_LIGHT_CHANGE_SWITCH, glaLightChangeSwitch);
        update.putExtra(EXTRA_TSR_SWITCH, tsrSwitch);
        update.putExtra(EXTRA_ERROR, reportedError(directTlcSupported));
        update.setPackage(RESTOREMODE_PACKAGE);
        sendBroadcast(update, BIND_PERMISSION);
    }
}
