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
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fail-closed, schema-pinned bridge for the OEM triggered lane-change switch.
 *
 * <p>User switches are written through the OEM ICanBusService TX58. TLC and traffic-light
 * recognition share one complete TX77 entitlement vector, so every update preserves the other
 * feature instead of clearing it. The vector is reasserted after CanBus reconnect and vehicle
 * wake, but user switches themselves are never restored automatically. A successful Binder
 * transaction is not treated as ECU acknowledgement: every user-switch write remains pending
 * until mandatory delayed TX57 readback.</p>
 */
public final class ApolloTlcService extends Service {
    private static final String TAG = "$$$ ApolloTlcService $$$";
    private static final String RESTOREMODE_PACKAGE = "ru.big.town.restoremode";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    public static final String ACTION_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.APOLLO_TLC_UPDATE";
    public static final String ACTION_REQUEST_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.REQUEST_APOLLO_TLC_UPDATE";

    public static final String EXTRA_CAN_CONNECTED = "canConnected";
    public static final String EXTRA_PROFILE_SUPPORTED = "profileSupported";
    public static final String EXTRA_DIRECT_TLC_MODE = "directTlcMode";
    public static final String EXTRA_MASTER_KNOWN = "masterKnown";
    public static final String EXTRA_MASTER_ENABLED = "masterEnabled";
    public static final String EXTRA_PENDING = "pending";
    public static final String EXTRA_GEAR = "gear";
    public static final String EXTRA_PLC_SWITCH = "plcSwitch";
    public static final String EXTRA_PLC_STATUS = "plcStatus";
    public static final String EXTRA_ANP_SWITCH = "anpSwitch";
    public static final String EXTRA_TLC_CAPABILITY = "tlcCapability";
    public static final String EXTRA_PLC_CAPABILITY_SA = "plcCapabilitySa";
    public static final String EXTRA_GLA_SWITCH = "glaSwitch";
    public static final String EXTRA_GLA_LIGHT_CHANGE_SWITCH = "glaLightChangeSwitch";
    public static final String EXTRA_TSR_SWITCH = "tsrSwitch";
    public static final String EXTRA_ERROR = "error";

    public static final String GLOBAL_MASTER_KEY = "open_voyah_apollo_master";

    private static final String ACTION_INTERNAL_QUERY =
            "ru.big.town.anative.internal.APOLLO_TLC_QUERY";
    private static final String ACTION_INTERNAL_SET =
            "ru.big.town.anative.internal.APOLLO_TLC_SET";
    private static final String ACTION_INTERNAL_MASTER_SET =
            "ru.big.town.anative.internal.APOLLO_MASTER_SET";
    private static final String ACTION_INTERNAL_GLA_SET =
            "ru.big.town.anative.internal.APOLLO_GLA_SET";
    private static final String ACTION_INTERNAL_GLA_SOUND_SET =
            "ru.big.town.anative.internal.APOLLO_GLA_SOUND_SET";
    private static final String ACTION_INTERNAL_TSR_SET =
            "ru.big.town.anative.internal.APOLLO_TSR_SET";
    private static final String EXTRA_ENABLED = "enabled";
    private static final String EXTRA_ARGUMENT_VALID = "argumentValid";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String WRITE_CANBUS_PERMISSION =
            "com.qinggan.permission.WRITE_CANBUS";
    private static final String CANBUS_CALLBACK_DESCRIPTOR =
            "com.qinggan.canbus.ICanBusServiceCallback";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final int TX_GET_GEAR_STATUS = 6;
    private static final int TX_ADD_CALLBACK = 28;
    private static final int TX_REMOVE_CALLBACK = 29;
    private static final int TX_GET_VEHICLE_STATE = 57;
    private static final int TX_SET_VEHICLE_STATE = 58;
    private static final int TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE = 77;
    private static final int CALLBACK_VEHICLE_STATE_CHANGED = 36;

    private static final long BIND_RETRY_MS = 5_000L;
    private static final long BIND_RETRY_MAX_MS = 60_000L;
    private static final long BIND_CONNECT_TIMEOUT_MS = 20_000L;
    private static final long ENTITLEMENT_SETTLE_MS = 1_000L;
    private static final long ENTITLEMENT_WAKE_DEBOUNCE_MS = 5_000L;
    private static final long DELAYED_READBACK_MS = 3_000L;
    private static final long METRICS_INTERVAL_MS = 30_000L;
    private static final int MAX_TRACKED_TRANSACTION =
            TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE;
    private static final int[] TRACKED_TRANSACTIONS = {
            TX_GET_GEAR_STATUS,
            TX_ADD_CALLBACK,
            TX_REMOVE_CALLBACK,
            TX_GET_VEHICLE_STATE,
            TX_SET_VEHICLE_STATE,
            TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE
    };

    /**
     * Owns the complete mutable Apollo/CanBus state machine. Service lifecycle, UI requests,
     * ServiceConnection callbacks and Binder callbacks only enqueue work here; no synchronous
     * CanBus transaction is allowed to run on the process main thread.
     */
    private HandlerThread canBusWorkerThread;
    private volatile Handler handler;
    private final ExecutorService schemaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ApolloTlcSchema");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean queryInFlight = new AtomicBoolean();
    private final AtomicLong callbackTotal = new AtomicLong();
    private final AtomicLong callbackRelevant = new AtomicLong();
    private final AtomicLong callbackIgnored = new AtomicLong();
    private final AtomicLong callbackPostRejected = new AtomicLong();
    private final AtomicLong callbackMalformed = new AtomicLong();
    private final AtomicLong callbackStale = new AtomicLong();
    private final AtomicLong queryCoalesced = new AtomicLong();
    private final AtomicLong wakeCoalesced = new AtomicLong();
    private final AtomicLong bindTimeouts = new AtomicLong();
    private final AtomicLong workerDispatchMaxUs = new AtomicLong();
    private final AtomicLong descriptorCount = new AtomicLong();
    private final AtomicLong descriptorTotalUs = new AtomicLong();
    private final AtomicLong descriptorMaxUs = new AtomicLong();
    private final AtomicLongArray transactionCount =
            new AtomicLongArray(MAX_TRACKED_TRANSACTION + 1);
    private final AtomicLongArray transactionTotalUs =
            new AtomicLongArray(MAX_TRACKED_TRANSACTION + 1);
    private final AtomicLongArray transactionMaxUs =
            new AtomicLongArray(MAX_TRACKED_TRANSACTION + 1);

    private IBinder canBusBinder;
    private IBinder canBusCallback;
    private ServiceConnection canBusConnection;
    private boolean canBusBindingRequested;
    private boolean canBusConnected;
    private boolean callbackAdded;
    private volatile boolean destroyed;
    private boolean requestReceiverRegistered;
    private boolean canBusVerificationPending;
    private int canBusVerificationGeneration;
    private IBinder pendingCanBusBinder;
    private int bindEpoch;
    private int activeBindEpoch;
    private int callbackEpoch;
    private volatile int activeCallbackEpoch;
    private int rebindAttempt;
    private long metricsWindowStartedAtMs;
    private Runnable bindConnectWatchdog;

    private boolean schemaCheckComplete;
    private boolean canBusSchemaMatches;
    private boolean runtimeProfileValid = true;
    private String canBusSchemaError = "profile_check_pending";
    private final EnumMap<ApolloTlcPolicy.Signal, Integer> runtimeSignalOrdinals =
            new EnumMap<>(ApolloTlcPolicy.Signal.class);
    private String lastError = ApolloTlcPolicy.ERROR_NONE;
    /** Sticky until a Settings.Global master write succeeds; prevents false OFF confirmation. */
    private String masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;

    private int gear = ApolloTlcPolicy.UNKNOWN;
    private int plcSwitch = ApolloTlcPolicy.UNKNOWN;
    private int plcStatus = ApolloTlcPolicy.UNKNOWN;
    private int anpSwitch = ApolloTlcPolicy.UNKNOWN;
    private int tlcCapability = ApolloTlcPolicy.UNKNOWN;
    private int plcCapabilitySa = ApolloTlcPolicy.UNKNOWN;
    private int glaSwitch = ApolloTlcPolicy.UNKNOWN;
    private int glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
    private int tsrSwitch = ApolloTlcPolicy.UNKNOWN;
    private int humVcuReady = ApolloTlcPolicy.UNKNOWN;
    private int bmsState = ApolloTlcPolicy.UNKNOWN;

    private boolean pending;
    private int pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
    private ApolloTlcPolicy.Signal pendingSignal;
    private int pendingBindEpoch;
    private int writeGeneration;
    /** Prevents automatic retries if the full-only vendor permission is unexpectedly unavailable. */
    private boolean writePermissionFailureHandled;
    private String entitlementReassertReason = "vehicle wake";
    private boolean entitlementReassertScheduled;

    private final Runnable rebindRunnable = () -> runWorkerSafely(
            "scheduled rebind", () -> revalidateCanBusAndBind("scheduled rebind"));
    private final Runnable entitlementReassertRunnable = () -> runWorkerSafely(
            "entitlement reassert", () -> {
                entitlementReassertScheduled = false;
                reassertCompositeEntitlements(entitlementReassertReason);
            });
    private final Runnable metricsRunnable = () -> runWorkerSafely(
            "metrics", this::logAndRescheduleMetrics);

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REQUEST_APOLLO_TLC_UPDATE.equals(intent.getAction())) {
                enqueueQuery();
            }
        }
    };

    private IBinder createCanBusCallback(final int registrationEpoch) {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == CALLBACK_VEHICLE_STATE_CHANGED) {
                    callbackTotal.incrementAndGet();
                    if (!ApolloTlcPolicy.epochCurrent(
                            destroyed, activeCallbackEpoch, registrationEpoch)) {
                        callbackStale.incrementAndGet();
                        return true;
                    }
                    try {
                        data.enforceInterface(CANBUS_CALLBACK_DESCRIPTOR);
                        int ordinal = ApolloTlcPolicy.UNKNOWN;
                        int id = ApolloTlcPolicy.UNKNOWN;
                        if (data.readInt() != 0) {
                            ordinal = data.readInt();
                            id = data.readInt();
                        }
                        int state = data.readInt();
                        ApolloTlcPolicy.Signal signal = ApolloTlcPolicy.Signal.fromId(id);
                        if (signal == null) {
                            callbackIgnored.incrementAndGet();
                            return true;
                        }
                        callbackRelevant.incrementAndGet();
                        final int callbackOrdinal = ordinal;
                        final int callbackState = state;
                        if (!postWorker(() -> {
                            if (!ApolloTlcPolicy.callbackEventCurrent(
                                    destroyed, activeCallbackEpoch,
                                    registrationEpoch, callbackAdded)) {
                                callbackStale.incrementAndGet();
                                return;
                            }
                            onVehicleStateCallback(callbackOrdinal, signal, callbackState);
                        })) {
                            callbackPostRejected.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        callbackMalformed.incrementAndGet();
                        Log.e(TAG, "Malformed VehicleState callback", e);
                        if (!postWorker(() -> {
                            if (!ApolloTlcPolicy.epochCurrent(
                                    destroyed, activeCallbackEpoch, registrationEpoch)) {
                                callbackStale.incrementAndGet();
                                return;
                            }
                            failRuntimeProfileClosed("profile_callback_malformed");
                            publishState();
                        })) {
                            callbackPostRejected.incrementAndGet();
                        }
                    }
                    return true;
                }
                // The OEM callback is large and oneway. Quietly accept methods this bridge ignores.
                if (code >= IBinder.FIRST_CALL_TRANSACTION
                        && code <= IBinder.LAST_CALL_TRANSACTION) {
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
    }

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
                    verifyConnectedCanBus(service);
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                ServiceConnection source = this;
                postWorker(() -> {
                    if (!isCurrentConnection(connectionEpoch, source)) return;
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

    /** Posts immediate work to the serial state-machine thread and records queue pressure. */
    private boolean postWorker(Runnable action) {
        Handler target = handler;
        if (destroyed || target == null) return false;
        final long enqueuedAtNs = SystemClock.elapsedRealtimeNanos();
        return target.post(() -> {
            long delayUs = Math.max(0L,
                    (SystemClock.elapsedRealtimeNanos() - enqueuedAtNs) / 1_000L);
            updateMax(workerDispatchMaxUs, delayUs);
            runWorkerSafely("queued work", action);
        });
    }

    /** Keeps one unexpected task failure from terminating the authoritative HandlerThread. */
    private void runWorkerSafely(String source, Runnable action) {
        if (destroyed) return;
        try {
            action.run();
        } catch (RuntimeException e) {
            Log.e(TAG, source + " failed", e);
            if (destroyed) return;
            if (pending) {
                ++writeGeneration;
                pending = false;
                pendingSignal = null;
                pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
                pendingBindEpoch = 0;
            }
            failRuntimeProfileClosed("worker_task_failed");
            try {
                publishState();
            } catch (RuntimeException publishError) {
                Log.e(TAG, "Cannot publish worker failure", publishError);
            }
        }
    }

    /** Coalesces Messenger, broadcast and sticky-service queries into one full CAN refresh. */
    private void enqueueQuery() {
        if (destroyed) return;
        if (!queryInFlight.compareAndSet(false, true)) {
            queryCoalesced.incrementAndGet();
            return;
        }
        if (!postWorker(() -> {
            try {
                handleQuery();
            } finally {
                queryInFlight.set(false);
            }
        })) {
            queryInFlight.set(false);
        }
    }

    private static void updateMax(AtomicLong target, long value) {
        long current = target.get();
        while (value > current && !target.compareAndSet(current, value)) {
            current = target.get();
        }
    }

    private static void updateMax(AtomicLongArray target, int index, long value) {
        long current = target.get(index);
        while (value > current && !target.compareAndSet(index, current, value)) {
            current = target.get(index);
        }
    }

    public static void requestQuery(Context context) {
        start(context, ACTION_INTERNAL_QUERY, false, true);
    }

    public static void requestTlcSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_SET, enabled, argumentValid);
    }

    public static void requestMasterSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_MASTER_SET, enabled, argumentValid);
    }

    public static void requestGlaSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_GLA_SET, enabled, argumentValid);
    }

    public static void requestGlaSoundSet(Context context, boolean enabled,
                                          boolean argumentValid) {
        start(context, ACTION_INTERNAL_GLA_SOUND_SET, enabled, argumentValid);
    }

    public static void requestTsrSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_TSR_SET, enabled, argumentValid);
    }

    public static void ensureStarted(Context context) {
        start(context, null, false, true);
    }

    private static void start(Context context, String action, boolean enabled,
                              boolean argumentValid) {
        Intent intent = new Intent(context, ApolloTlcService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ENABLED, enabled);
        intent.putExtra(EXTRA_ARGUMENT_VALID, argumentValid);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start ApolloTlcService for " + action, e);
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
            registerReceiver(requestReceiver,
                    new IntentFilter(ACTION_REQUEST_APOLLO_TLC_UPDATE),
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
            if (!BuildConfig.IS_FULL) {
                forceMasterOff("light startup");
            }
            // Light is intentionally inert: no vendor class loading or qg.canbus transactions.
            // Full binds only after the installed CanBus VehicleState schema matches.
            if (BuildConfig.IS_FULL) {
                if (hasWriteCanBusPermission()) {
                    startSchemaCheck();
                } else {
                    failWritePermissionClosed();
                }
            }
            publishState();
            metricsWindowStartedAtMs = SystemClock.elapsedRealtime();
            handler.postDelayed(metricsRunnable, METRICS_INTERVAL_MS);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final boolean enabled = intent != null
                && intent.getBooleanExtra(EXTRA_ENABLED, false);
        final boolean valid = intent != null
                && intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
        if (ACTION_INTERNAL_SET.equals(action)
                || ACTION_INTERNAL_MASTER_SET.equals(action)
                || ACTION_INTERNAL_GLA_SET.equals(action)
                || ACTION_INTERNAL_GLA_SOUND_SET.equals(action)
                || ACTION_INTERNAL_TSR_SET.equals(action)) {
            postWorker(() -> {
                if (!BuildConfig.IS_FULL) forceMasterOff("light command");
                if (ACTION_INTERNAL_SET.equals(action)) {
                    handleTlcSet(enabled, valid);
                } else if (ACTION_INTERNAL_MASTER_SET.equals(action)) {
                    handleMasterSet(enabled, valid);
                } else if (ACTION_INTERNAL_GLA_SET.equals(action)) {
                    handleGlaSet(enabled, valid);
                } else if (ACTION_INTERNAL_GLA_SOUND_SET.equals(action)) {
                    handleGlaSoundSet(enabled, valid);
                } else {
                    handleTsrSet(enabled, valid);
                }
            });
        } else {
            enqueueQuery();
        }
        return BuildConfig.IS_FULL ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        queryInFlight.set(false);
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
                ++writeGeneration;
                pending = false;
                pendingSignal = null;
                pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
                pendingBindEpoch = 0;
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

    private void handleQuery() {
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light query");
            invalidateCanSnapshot();
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
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
        if (canBusConnected && callbackAdded && !refreshFromCan("query")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        } else if (!canBusConnected || !callbackAdded) {
            invalidateCanSnapshot();
        }
        publishState();
    }

    private void handleMasterSet(boolean enabled, boolean argumentValid) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light master command");
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        // This release is deliberately direct-only. It must never invoke the OEM subscription
        // manager that writes the unrelated 18-field Apollo entitlement bundle.
        writeMaster(false);
        lastError = enabled
                ? ApolloTlcPolicy.ERROR_MASTER_NOT_USED_DIRECT
                : ApolloTlcPolicy.ERROR_NONE;
        publishState();
        return;
    }

    private void handleTlcSet(boolean enabled, boolean argumentValid) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light TLC command");
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }

        // Read only the direct TLC inputs. The single parking gate is checked here; independent
        // GLA/TSR commands never read gear.
        if (!refreshTlcPrewrite(enabled)) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
            publishState();
            return;
        }
        boolean directTlcMode = isDirectTlcSupported();
        if (enabled && !hasCompleteCompositeSwitchSnapshot()) {
            lastError = "composite_switch_state_unknown";
            publishState();
            return;
        }
        String blocked = ApolloTlcPolicy.directTlcBlockReason(
                true, directTlcMode, canBusConnected && callbackAdded,
                pending, gear, plcSwitch);
        if (!blocked.isEmpty()) {
            lastError = blocked;
            Log.w(TAG, "PLC_SWITCH write blocked: " + blocked);
            publishState();
            return;
        }

        int desiredState = ApolloTlcPolicy.requestedPlcState(enabled);
        if (enabled) {
            queueEntitledFeatureEnable(
                    ApolloTlcPolicy.Signal.PLC_SWITCH, desiredState, "PLC_SWITCH",
                    true, glaSwitch == ApolloTlcPolicy.MODULE_ON);
        } else {
            queueSignalWrite(ApolloTlcPolicy.Signal.PLC_SWITCH,
                    desiredState, "PLC_SWITCH");
        }
    }

    private void handleGlaSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.GLA_SWITCH,
                ApolloTlcPolicy.requestedPlcState(enabled), argumentValid,
                false, enabled);
    }

    private void handleGlaSoundSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH,
                ApolloTlcPolicy.requestedPlcState(enabled), argumentValid,
                true, false);
    }

    private void handleTsrSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.TSR_SWITCH,
                ApolloTlcPolicy.requestedTsrState(enabled), argumentValid,
                false, false);
    }

    private void handleIndependentSwitchSet(ApolloTlcPolicy.Signal signal, int desiredState,
                                            boolean argumentValid,
                                            boolean requiresRecognition,
                                            boolean enableTrafficLightEntitlements) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }
        if (!refreshIndependentPrewrite(
                signal, requiresRecognition, enableTrafficLightEntitlements)) {
            if (runtimeProfileValid) lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            publishState();
            return;
        }
        String blocked = ApolloTlcPolicy.directSwitchBlockReason(
                true, isDirectTlcSupported(), canBusConnected && callbackAdded,
                pending, cachedState(signal));
        if (blocked.isEmpty() && requiresRecognition
                && glaSwitch != ApolloTlcPolicy.MODULE_ON) {
            blocked = "traffic_light_recognition_disabled";
        }
        if (!blocked.isEmpty()) {
            lastError = blocked;
            publishState();
            return;
        }
        if (enableTrafficLightEntitlements) {
            if (!hasCompleteCompositeSwitchSnapshot()) {
                lastError = "composite_switch_state_unknown";
                publishState();
                return;
            }
            queueEntitledFeatureEnable(
                    ApolloTlcPolicy.Signal.GLA_SWITCH, desiredState, "GLA_SWITCH",
                    plcSwitch == ApolloTlcPolicy.MODULE_ON, true);
        } else {
            queueSignalWrite(signal, desiredState, signal.name());
        }
    }

    /**
     * Sends one complete 18-key entitlement vector before enabling a dependent user switch.
     * TX77 returning zero means only that the OEM AsyncTask accepted the request, so TX58 is
     * delayed long enough for command 126 to leave the head unit. Neither stage is retried.
     */
    private void queueEntitledFeatureEnable(ApolloTlcPolicy.Signal signal, int desiredState,
                                            String logName, boolean tlcEnabled,
                                            boolean trafficLightEnabled) {
        int generation = beginPendingWrite(signal, desiredState);
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            return;
        }
        try {
            int result = setCompositeEntitlements(tlcEnabled, trafficLightEnabled);
            if (result != 0) {
                failPendingWrite(generation, "feature_entitlement_rejected");
                Log.e(TAG, "Composite TX77 rejected; result=" + result);
                return;
            }
            Log.i(TAG, "Composite TX77 queued before " + logName
                    + ": tlc=" + tlcEnabled + " trafficLight=" + trafficLightEnabled
                    + " generation=" + generation);
        } catch (RemoteException | RuntimeException e) {
            failPendingWrite(generation, "feature_entitlement_tx_failed");
            Log.e(TAG, "Composite TX77 failed; no retry", e);
            return;
        }
        handler.postDelayed(() -> runWorkerSafely("entitlement settle",
                        () -> continueEntitledFeatureEnable(
                                generation, signal, desiredState, logName)),
                ENTITLEMENT_SETTLE_MS);
    }

    private void continueEntitledFeatureEnable(int generation, ApolloTlcPolicy.Signal signal,
                                               int desiredState, String logName) {
        if (!ApolloTlcPolicy.writeSessionCurrent(
                destroyed, pending, writeGeneration, generation,
                activeBindEpoch, pendingBindEpoch)) {
            if (!destroyed && generation == writeGeneration && pending
                    && pendingBindEpoch != activeBindEpoch) {
                failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            }
            return;
        }
        if (pendingSignal != signal || pendingDesiredState != desiredState) return;
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded
                || !refreshCompositeSwitches("entitlement settle")) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_STATE_READ_FAILED);
            return;
        }
        if (!ApolloTlcPolicy.isModuleState(cachedState(signal))) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_INVALID_SWITCH_STATE);
            return;
        }
        transmitPendingSignal(generation, signal, desiredState, logName);
    }

    private void queueSignalWrite(ApolloTlcPolicy.Signal signal, int desiredState,
                                  String logName) {
        int generation = beginPendingWrite(signal, desiredState);
        transmitPendingSignal(generation, signal, desiredState, logName);
    }

    private int beginPendingWrite(ApolloTlcPolicy.Signal signal, int desiredState) {
        int generation = ++writeGeneration;
        pending = true;
        pendingSignal = signal;
        pendingDesiredState = desiredState;
        pendingBindEpoch = activeBindEpoch;
        lastError = ApolloTlcPolicy.ERROR_NONE;
        publishState();
        return generation;
    }

    private void transmitPendingSignal(int generation, ApolloTlcPolicy.Signal signal,
                                       int desiredState, String logName) {
        if (!ApolloTlcPolicy.writeSessionCurrent(
                destroyed, pending, writeGeneration, generation,
                activeBindEpoch, pendingBindEpoch)) {
            if (!destroyed && generation == writeGeneration && pending
                    && pendingBindEpoch != activeBindEpoch) {
                failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            }
            return;
        }
        if (pendingSignal != signal || pendingDesiredState != desiredState) return;
        // Re-check immediately before TX58 in case package permissions changed while this command
        // was being validated. No Binder write is attempted on an ungranted permission.
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            return;
        }

        try {
            setVehicleState(signal, desiredState);
            Log.i(TAG, logName + " TX58 queued; desired=" + desiredState
                    + " generation=" + generation);
        } catch (RemoteException | RuntimeException e) {
            failPendingWrite(generation, "tx58_failed");
            Log.e(TAG, logName + " TX58 failed; no retry", e);
            return;
        }

        // Keep the command pending for the complete ECU window. A full immediate snapshot used to
        // add five synchronous transactions without acknowledging the write; the delayed targeted
        // TX57 below remains the only authoritative confirmation.
        publishState();
        handler.postDelayed(() -> runWorkerSafely("delayed readback",
                () -> finishDelayedReadback(generation)), DELAYED_READBACK_MS);
    }

    private void failPendingWrite(int generation, String error) {
        if (generation != writeGeneration) return;
        clearPendingWrite(generation);
        lastError = error;
        publishState();
    }

    private void clearPendingWrite(int generation) {
        if (generation != writeGeneration) return;
        pending = false;
        pendingSignal = null;
        pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
        pendingBindEpoch = 0;
    }

    private void finishDelayedReadback(int generation) {
        if (!ApolloTlcPolicy.writeSessionCurrent(
                destroyed, pending, writeGeneration, generation,
                activeBindEpoch, pendingBindEpoch)) {
            if (!destroyed && generation == writeGeneration && pending
                    && pendingBindEpoch != activeBindEpoch) {
                failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            }
            return;
        }
        if (!runtimeProfileValid) {
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            pendingBindEpoch = 0;
            publishState();
            return;
        }
        boolean readOk = canBusConnected && callbackAdded && refreshPendingReadback();
        boolean shouldUpdateCompositeEntitlements = false;
        pending = false;
        if (!readOk) {
            if (runtimeProfileValid) {
                lastError = "delayed_readback_failed";
            }
        } else if (pendingSignal == null || cachedState(pendingSignal) != pendingDesiredState) {
            lastError = "readback_mismatch";
        } else {
            lastError = ApolloTlcPolicy.ERROR_NONE;
            shouldUpdateCompositeEntitlements = ApolloTlcPolicy.readbackNeedsPeerSwitch(
                    pendingSignal, pendingDesiredState);
        }
        pendingSignal = null;
        pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
        pendingBindEpoch = 0;
        if (shouldUpdateCompositeEntitlements) {
            updateCompositeEntitlementsAfterConfirmedSwitchOff();
        } else {
            publishState();
        }
    }

    /**
     * Confirms only the signal written by the pending command. A confirmed PLC/GLA OFF also reads
     * the peer switch because the subsequent TX77 must preserve that peer's live entitlement.
     */
    private boolean refreshPendingReadback() {
        ApolloTlcPolicy.Signal signal = pendingSignal;
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
                || !canBusConnected || canBusBinder == null || signal == null) {
            if (signal != null) setCachedState(signal, ApolloTlcPolicy.UNKNOWN);
            return false;
        }
        try {
            setCachedState(signal, getVehicleState(signal));
            if (ApolloTlcPolicy.readbackNeedsPeerSwitch(signal, pendingDesiredState)) {
                if (signal == ApolloTlcPolicy.Signal.PLC_SWITCH) {
                    glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
                } else if (signal == ApolloTlcPolicy.Signal.GLA_SWITCH) {
                    plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
                }
            }
            Log.i(TAG, "targeted readback: signal=" + signal
                    + " actual=" + cachedState(signal)
                    + " desired=" + pendingDesiredState);
            return true;
        } catch (RemoteException | RuntimeException e) {
            setCachedState(signal, ApolloTlcPolicy.UNKNOWN);
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, "Targeted readback failed for " + signal, e);
            return false;
        }
    }

    /**
     * The target user switch is already confirmed OFF. Rebuilding from both live switches clears
     * only its entitlement pair while preserving the other feature. TX77 has no ECU
     * acknowledgement and is not retried automatically.
     */
    private void updateCompositeEntitlementsAfterConfirmedSwitchOff() {
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }
        if (!hasCompleteCompositeSwitchSnapshot()) {
            lastError = "composite_switch_state_unknown";
            publishState();
            return;
        }
        try {
            boolean tlcEnabled = plcSwitch == ApolloTlcPolicy.MODULE_ON;
            boolean trafficLightEnabled = glaSwitch == ApolloTlcPolicy.MODULE_ON;
            int result = setCompositeEntitlements(tlcEnabled, trafficLightEnabled);
            if (result != 0) {
                lastError = "feature_entitlement_disable_rejected";
                Log.e(TAG, "Composite disable TX77 rejected; result=" + result);
            } else {
                lastError = ApolloTlcPolicy.ERROR_NONE;
                Log.i(TAG, "Composite TX77 queued after confirmed OFF: tlc=" + tlcEnabled
                        + " trafficLight=" + trafficLightEnabled);
            }
        } catch (RemoteException | RuntimeException e) {
            lastError = "feature_entitlement_disable_tx_failed";
            Log.e(TAG, "Composite disable TX77 failed; no retry", e);
        }
        publishState();
    }

    private boolean hasCompleteCompositeSwitchSnapshot() {
        return ApolloTlcPolicy.compositeSwitchStatesValid(plcSwitch, glaSwitch);
    }

    private int cachedState(ApolloTlcPolicy.Signal signal) {
        switch (signal) {
            case PLC_FUNCTION_STATUS:
                return plcStatus;
            case PLC_SWITCH:
                return plcSwitch;
            case ANP_SWITCH:
                return anpSwitch;
            case GLA_SWITCH:
                return glaSwitch;
            case GLA_LIGHT_CHANGE_SWITCH:
                return glaLightChangeSwitch;
            case TSR_SWITCH:
                return tsrSwitch;
            case TLC_FUNC_ENABLE:
                return tlcCapability;
            case PLC_FUNC_ENABLE_SA:
                return plcCapabilitySa;
            case HUM_VCU_READY:
                return humVcuReady;
            case BMS_STATE:
                return bmsState;
            default:
                return ApolloTlcPolicy.UNKNOWN;
        }
    }

    private void onVehicleStateCallback(int ordinal, ApolloTlcPolicy.Signal signal, int state) {
        if (destroyed || !runtimeProfileValid) return;
        Integer expectedOrdinal = runtimeSignalOrdinals.get(signal);
        if (expectedOrdinal == null || expectedOrdinal != ordinal) {
            failRuntimeProfileClosed("profile_callback_mismatch");
            Log.e(TAG, "Pinned VehicleState mismatch for id=" + signal.id
                    + ": expected ordinal=" + expectedOrdinal + " actual=" + ordinal);
            publishState();
            return;
        }
        int previousState = cachedState(signal);
        setCachedState(signal, state);
        if (ApolloTlcPolicy.shouldScheduleWakeReassert(signal, previousState, state)) {
            scheduleCompositeEntitlementReassert(signal.name());
        }
        // HUM/BMS are edge-triggered wake inputs only and do not change the UI snapshot. Caching
        // their last values prevents a steady eligible-state stream from reasserting TX77 forever.
        if (signal != ApolloTlcPolicy.Signal.HUM_VCU_READY
                && signal != ApolloTlcPolicy.Signal.BMS_STATE
                && previousState != state) {
            publishState();
        }
    }

    /** Coalesces the two OEM wake signals into one complete entitlement write. */
    private void scheduleCompositeEntitlementReassert(String reason) {
        if (destroyed || !BuildConfig.IS_FULL) return;
        entitlementReassertReason = reason;
        if (entitlementReassertScheduled) {
            wakeCoalesced.incrementAndGet();
            return;
        }
        entitlementReassertScheduled = true;
        handler.postDelayed(entitlementReassertRunnable, ENTITLEMENT_WAKE_DEBOUNCE_MS);
    }

    /**
     * Reasserts only permissions for user switches that the vehicle itself currently reports ON.
     * This restores ECU capability state after sleep without automatically changing a switch.
     */
    private void reassertCompositeEntitlements(String reason) {
        if (destroyed) return;
        if (pending) {
            scheduleCompositeEntitlementReassert(reason);
            return;
        }
        if (!runtimeProfileValid || !canBusConnected
                || !callbackAdded || canBusBinder == null || !hasWriteCanBusPermission()) {
            return;
        }
        if (!refreshCompositeSwitches("entitlement reassert " + reason)) {
            publishState();
            return;
        }
        boolean tlcEnabled = plcSwitch == ApolloTlcPolicy.MODULE_ON;
        boolean trafficLightEnabled = glaSwitch == ApolloTlcPolicy.MODULE_ON;
        if (!hasCompleteCompositeSwitchSnapshot()) {
            lastError = "composite_switch_state_unknown";
            publishState();
            return;
        }
        if (!tlcEnabled && !trafficLightEnabled) {
            publishState();
            return;
        }
        try {
            int result = setCompositeEntitlements(tlcEnabled, trafficLightEnabled);
            if (result == 0) {
                lastError = ApolloTlcPolicy.ERROR_NONE;
                Log.i(TAG, "Composite TX77 reassert queued after " + reason
                        + ": tlc=" + tlcEnabled
                        + " trafficLight=" + trafficLightEnabled);
            } else {
                lastError = "feature_entitlement_reassert_rejected";
                Log.e(TAG, "Composite TX77 reassert rejected after " + reason
                        + "; result=" + result);
            }
        } catch (RemoteException | RuntimeException e) {
            lastError = "feature_entitlement_reassert_tx_failed";
            Log.e(TAG, "Composite TX77 reassert failed after " + reason, e);
        }
        publishState();
    }

    /** Reads only the state required by an independent GLA/GLA-sound/TSR command. */
    private boolean refreshIndependentPrewrite(ApolloTlcPolicy.Signal signal,
                                               boolean requiresRecognition,
                                               boolean requiresCompositeSwitches) {
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
                || !canBusConnected || canBusBinder == null) {
            invalidateCanSnapshot();
            return false;
        }
        try {
            setCachedState(signal, getVehicleState(signal));
            if (requiresRecognition && signal != ApolloTlcPolicy.Signal.GLA_SWITCH) {
                glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
            }
            if (requiresCompositeSwitches) {
                if (signal != ApolloTlcPolicy.Signal.PLC_SWITCH) {
                    plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
                }
                if (signal != ApolloTlcPolicy.Signal.GLA_SWITCH) {
                    glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
                }
            }
            Log.i(TAG, "targeted prewrite: signal=" + signal
                    + " state=" + cachedState(signal)
                    + " plc=" + plcSwitch + " gla=" + glaSwitch);
            return true;
        } catch (RemoteException | RuntimeException e) {
            invalidateCanSnapshot();
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, "Targeted prewrite failed for " + signal, e);
            return false;
        }
    }

    /** Reads PLC, the shared GLA entitlement peer when enabling, and the one TLC gear gate. */
    private boolean refreshTlcPrewrite(boolean enabling) {
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
                || !canBusConnected || canBusBinder == null) {
            invalidateCanSnapshot();
            return false;
        }
        try {
            plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
            if (enabling) {
                glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
            }
            GearReading reading = getGearStatus();
            if (!reading.valid) {
                failRuntimeProfileClosed("profile_gear_parcel_mismatch");
                Log.e(TAG, "Invalid GearState parcel during TLC prewrite ordinal="
                        + reading.ordinal + " value=" + reading.value);
                return false;
            }
            gear = reading.value;
            Log.i(TAG, "targeted TLC prewrite: gear=" + gear + " plc=" + plcSwitch
                    + (enabling ? " gla=" + glaSwitch : ""));
            return true;
        } catch (RemoteException | RuntimeException e) {
            invalidateCanSnapshot();
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, "Targeted TLC prewrite failed", e);
            return false;
        }
    }

    /** Reads the only two switches needed to construct the shared TX77 entitlement vector. */
    private boolean refreshCompositeSwitches(String reason) {
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
                || !canBusConnected || canBusBinder == null) {
            plcSwitch = ApolloTlcPolicy.UNKNOWN;
            glaSwitch = ApolloTlcPolicy.UNKNOWN;
            return false;
        }
        try {
            plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
            glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
            Log.i(TAG, reason + ": plc=" + plcSwitch + " gla=" + glaSwitch);
            return true;
        } catch (RemoteException | RuntimeException e) {
            plcSwitch = ApolloTlcPolicy.UNKNOWN;
            glaSwitch = ApolloTlcPolicy.UNKNOWN;
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, reason + " failed", e);
            return false;
        }
    }

    private boolean refreshFromCan(String reason) {
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
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

    /**
     * Permanently closes this service instance after a pinned protocol mismatch. The durable
     * entitlement is cleared once, without retry; a failed clear remains visible through
     * masterPersistenceError while runtimeProfileValid blocks every TX58 in memory.
     */
    private void failRuntimeProfileClosed(String error) {
        if (!runtimeProfileValid) return;
        runtimeProfileValid = false;
        invalidateCanSnapshot();
        if (pending) {
            ++writeGeneration;
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            pendingBindEpoch = 0;
        }
        lastError = error;
        if (!writeMaster(false)) {
            Log.e(TAG, "Cannot clear Apollo master after permanent runtime mismatch: " + error);
        }
        releaseCanBusBinding(error);
    }

    /** Closes entitlement and all Binder gates once; there is deliberately no automatic retry. */
    private void failWritePermissionClosed() {
        lastError = ApolloTlcPolicy.ERROR_WRITE_PERMISSION_MISSING;
        invalidateCanSnapshot();
        if (pending) {
            ++writeGeneration;
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            pendingBindEpoch = 0;
        }
        boolean shouldClearMaster = !writePermissionFailureHandled;
        writePermissionFailureHandled = true;
        releaseCanBusBinding("WRITE_CANBUS permission missing");
        if (!shouldClearMaster) return;
        if (!writeMaster(false)) {
            Log.e(TAG, "Cannot clear Apollo master after WRITE_CANBUS permission loss");
        }
    }

    private void invalidateCanSnapshot() {
        gear = ApolloTlcPolicy.UNKNOWN;
        plcSwitch = ApolloTlcPolicy.UNKNOWN;
        plcStatus = ApolloTlcPolicy.UNKNOWN;
        anpSwitch = ApolloTlcPolicy.UNKNOWN;
        tlcCapability = ApolloTlcPolicy.UNKNOWN;
        plcCapabilitySa = ApolloTlcPolicy.UNKNOWN;
        glaSwitch = ApolloTlcPolicy.UNKNOWN;
        glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
        tsrSwitch = ApolloTlcPolicy.UNKNOWN;
        humVcuReady = ApolloTlcPolicy.UNKNOWN;
        bmsState = ApolloTlcPolicy.UNKNOWN;
    }

    private void setCachedState(ApolloTlcPolicy.Signal signal, int state) {
        switch (signal) {
            case PLC_FUNCTION_STATUS:
                plcStatus = state;
                break;
            case PLC_SWITCH:
                plcSwitch = state;
                break;
            case ANP_SWITCH:
                anpSwitch = state;
                break;
            case GLA_SWITCH:
                glaSwitch = state;
                break;
            case GLA_LIGHT_CHANGE_SWITCH:
                glaLightChangeSwitch = state;
                break;
            case TSR_SWITCH:
                tsrSwitch = state;
                break;
            case TLC_FUNC_ENABLE:
                tlcCapability = state;
                break;
            case PLC_FUNC_ENABLE_SA:
                plcCapabilitySa = state;
                break;
            case HUM_VCU_READY:
                humVcuReady = state;
                break;
            case BMS_STATE:
                bmsState = state;
                break;
        }
    }

    private int getVehicleState(ApolloTlcPolicy.Signal signal) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            writeVehicleState(data, signal);
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

    private void setVehicleState(ApolloTlcPolicy.Signal signal, int state)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            writeVehicleState(data, signal);
            data.writeInt(state);
            if (!transactCanBus(TX_SET_VEHICLE_STATE, data, reply)) {
                throw new RemoteException("TX58 rejected");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * OEM TX77 argument order is air-condition bundle first, vehicle bundle second.
     * The vehicle bundle is intentionally complete because the vendor implementation starts its
     * shared entitlement bit buffer at zero before applying supplied keys.
     */
    private int setCompositeEntitlements(boolean tlcEnabled, boolean trafficLightEnabled)
            throws RemoteException {
        Bundle vehicleBundle = new Bundle();
        for (ApolloTlcPolicy.Entitlement entitlement
                : ApolloTlcPolicy.Entitlement.values()) {
            vehicleBundle.putInt(
                    entitlement.name(),
                    entitlement.compositeValue(tlcEnabled, trafficLightEnabled));
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(0); // air-condition bundle is null
            data.writeInt(1); // vehicle bundle is present
            vehicleBundle.writeToParcel(data, 0);
            if (!transactCanBus(
                    TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE, data, reply)) {
                throw new RemoteException("TX77 rejected");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Presence marker + runtime-resolved VehicleState.writeToParcel(ordinal, stable id). */
    private void writeVehicleState(Parcel data, ApolloTlcPolicy.Signal signal) {
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

    /** Performs one measured synchronous transaction, always from the serial worker thread. */
    private boolean transactCanBus(int transactionCode, Parcel data, Parcel reply)
            throws RemoteException {
        if (destroyed && transactionCode != TX_REMOVE_CALLBACK) {
            throw new RemoteException("Apollo service destroyed");
        }
        IBinder binder = canBusBinder;
        if (binder == null) throw new RemoteException("CanBus binder unavailable");
        long startedAtNs = SystemClock.elapsedRealtimeNanos();
        try {
            return binder.transact(transactionCode, data, reply, 0);
        } finally {
            long durationUs = Math.max(0L,
                    (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000L);
            if (transactionCode >= 0 && transactionCode <= MAX_TRACKED_TRANSACTION) {
                transactionCount.incrementAndGet(transactionCode);
                transactionTotalUs.addAndGet(transactionCode, durationUs);
                updateMax(transactionMaxUs, transactionCode, durationUs);
            }
        }
    }

    private String readCanBusDescriptor(IBinder service) throws RemoteException {
        long startedAtNs = SystemClock.elapsedRealtimeNanos();
        try {
            return service.getInterfaceDescriptor();
        } finally {
            long durationUs = Math.max(0L,
                    (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000L);
            descriptorCount.incrementAndGet();
            descriptorTotalUs.addAndGet(durationUs);
            updateMax(descriptorMaxUs, durationUs);
        }
    }

    /** Re-resolves the installed VehicleState table before the first Binder transaction. */
    private void verifyConnectedCanBus(IBinder candidate) {
        if (destroyed || !BuildConfig.IS_FULL || !schemaCheckComplete) {
            rejectCanBusVerification("profile_canbus_schema_unavailable");
            return;
        }
        final int generation = beginCanBusVerification(candidate);
        if (!submitSchemaTask("connected CanBus verification", () -> {
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            postWorker(() -> {
                if (!verificationResultCurrent(generation, candidate)) return;
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                applyVehicleStateSchema(result);
                if (!result.matches) {
                    rejectCanBusVerification(result.error);
                    return;
                }
                activateVerifiedCanBus(candidate);
            });
        }) && !destroyed && verificationResultCurrent(generation, candidate)) {
            rejectCanBusVerification("profile_executor_unavailable");
        }
    }

    private void activateVerifiedCanBus(IBinder service) {
        if (destroyed) return;
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
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
        callbackAdded = false;
        int registrationEpoch = ++callbackEpoch;
        activeCallbackEpoch = registrationEpoch;
        canBusCallback = createCanBusCallback(registrationEpoch);
        lastError = ApolloTlcPolicy.ERROR_NONE;
        Log.i(TAG, "CanBusService connected after current schema verification");
        addCanBusCallback();
        if (!callbackAdded) {
            String registrationError = ApolloTlcPolicy.ERROR_NONE.equals(lastError)
                    ? "callback_unavailable" : lastError;
            restartCanBusBinding(registrationError);
            return;
        }
        rebindAttempt = 0;
        if (!refreshFromCan("connect")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        } else {
            scheduleCompositeEntitlementReassert("CanBus reconnect");
        }
        publishState();
    }

    /** Re-resolves the runtime VehicleState schema, then creates a fresh binding on success. */
    private void revalidateCanBusAndBind(String reason) {
        if (destroyed || !BuildConfig.IS_FULL || !schemaCheckComplete
                || !hasWriteCanBusPermission()
                || canBusVerificationPending) {
            return;
        }
        // This path is entered only after disconnect/death/rejection. Mark disconnected before
        // unbind so release cannot emit TX29 to an unverified or dead Binder identity.
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
        releaseCanBusBinding(reason);

        final int generation = beginCanBusVerification(null);
        if (!submitSchemaTask("CanBus revalidation", () -> {
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            postWorker(() -> {
                if (!verificationResultCurrent(generation, null)) return;
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
        invalidateCallbackIdentity();
        invalidateCanSnapshot();
        publishState();
        return generation;
    }

    private boolean verificationResultCurrent(int generation, IBinder candidate) {
        return canBusVerificationPending
                && ApolloTlcPolicy.verificationResultCurrent(
                BuildConfig.IS_FULL, destroyed,
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
        invalidateCallbackIdentity();
        invalidateCanSnapshot();
        writeMaster(false);
        releaseCanBusBinding("CanBus schema verification failed");
        publishState();
    }

    private void ensureCanBusBound() {
        if (destroyed || canBusBindingRequested || !isBinderProfilePinned()) return;
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
        if (destroyed || !BuildConfig.IS_FULL || !schemaCheckComplete
                || !hasWriteCanBusPermission()) return;
        handler.removeCallbacks(rebindRunnable);
        int exponent = Math.min(rebindAttempt, 4);
        long delayMs = Math.min(BIND_RETRY_MAX_MS, BIND_RETRY_MS << exponent);
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
            bindTimeouts.incrementAndGet();
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
        invalidateCanBusIdentity(reason);
        releaseCanBusBinding(reason);
        scheduleCanBusRebind();
    }

    private void invalidateCanBusIdentity(String error) {
        handler.removeCallbacks(entitlementReassertRunnable);
        entitlementReassertScheduled = false;
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        canBusSchemaMatches = false;
        canBusSchemaError = "profile_canbus_revalidation_pending";
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        invalidateCallbackIdentity();
        invalidateCanSnapshot();
        if (pending) {
            ++writeGeneration;
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            pendingBindEpoch = 0;
        }
        lastError = error;
        publishState();
    }

    private void releaseCanBusBinding(String reason) {
        handler.removeCallbacks(rebindRunnable);
        cancelBindConnectWatchdog();
        handler.removeCallbacks(entitlementReassertRunnable);
        entitlementReassertScheduled = false;
        if (canBusConnected && callbackAdded) removeCanBusCallback();
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
        invalidateCallbackIdentity();
    }

    private void invalidateCallbackIdentity() {
        activeCallbackEpoch = ++callbackEpoch;
        callbackAdded = false;
        canBusCallback = null;
    }

    private void addCanBusCallback() {
        if (!canBusConnected || canBusBinder == null
                || canBusCallback == null || callbackAdded) return;
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallback);
            if (!transactCanBus(TX_ADD_CALLBACK, data, reply)) {
                throw new RemoteException("TX28 rejected");
            }
            reply.readException();
            int result = reply.readInt();
            if (!ApolloTlcPolicy.callbackRegistrationAccepted(result)) {
                invalidateCallbackIdentity();
                invalidateCanSnapshot();
                lastError = "callback_unavailable";
                Log.e(TAG, "CanBus callback registration rejected (TX28 result="
                        + result + ", expected 1)");
                return;
            }
            callbackAdded = true;
        } catch (RemoteException | RuntimeException e) {
            invalidateCallbackIdentity();
            invalidateCanSnapshot();
            lastError = "callback_register_failed";
            Log.e(TAG, "Cannot add CanBus callback", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void removeCanBusCallback() {
        if (!canBusConnected || canBusBinder == null
                || canBusCallback == null || !callbackAdded) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallback);
            transactCanBus(TX_REMOVE_CALLBACK, data, reply);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Cannot remove CanBus callback", e);
        } finally {
            callbackAdded = false;
            reply.recycle();
            data.recycle();
        }
    }

    private void startSchemaCheck() {
        if (!submitSchemaTask("startup schema verification", () -> {
            VehicleStateSchemaResult schema = resolveVehicleStateSchema();
            postWorker(() -> {
                if (destroyed) return;
                schemaCheckComplete = true;
                applyVehicleStateSchema(schema);
                if (BuildConfig.IS_FULL && !hasWriteCanBusPermission()) {
                    failWritePermissionClosed();
                } else if (!schema.matches && BuildConfig.IS_FULL) {
                    writeMaster(false);
                } else if (schema.matches) {
                    // This release exposes only direct TLC. Keep the unrelated global Apollo
                    // entitlement switch off even when an older installation persisted it as ON.
                    forceMasterOff("direct TLC startup");
                }
                if (isBinderProfilePinned()) {
                    ensureCanBusBound();
                } else {
                    invalidateCanSnapshot();
                }
                publishState();
            });
        }) && !destroyed) {
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
            for (ApolloTlcPolicy.Entitlement entitlement
                    : ApolloTlcPolicy.Entitlement.values()) {
                Enum value = Enum.valueOf(enumClass, entitlement.name());
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer)
                        || ((Integer) stableId) != entitlement.id) {
                    Log.e(TAG, "VehicleState id mismatch for " + entitlement.name());
                    return VehicleStateSchemaResult.failed(
                            "profile_canbus_schema_mismatch");
                }
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

    /** Direct TLC needs only the allow-listed OEM Binder ABI; it does not use the Frida profile. */
    private boolean isDirectTlcSupported() {
        return BuildConfig.IS_FULL && schemaCheckComplete && canBusSchemaMatches
                && runtimeProfileValid && hasWriteCanBusPermission();
    }

    private boolean isBinderProfilePinned() {
        boolean writePermissionGranted = BuildConfig.IS_FULL
                && hasWriteCanBusPermission();
        return ApolloTlcPolicy.binderProfilePinned(
                BuildConfig.IS_FULL, schemaCheckComplete, canBusSchemaMatches,
                writePermissionGranted);
    }

    private boolean hasWriteCanBusPermission() {
        return checkSelfPermission(WRITE_CANBUS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static final class MasterSetting {
        final boolean known;
        final boolean enabled;

        MasterSetting(boolean known, boolean enabled) {
            this.known = known;
            this.enabled = enabled;
        }
    }

    private MasterSetting readPersistedMaster() {
        try {
            boolean enabled = Settings.Global.getInt(
                    getContentResolver(), GLOBAL_MASTER_KEY, 0) == 1;
            if ("master_read_failed".equals(masterPersistenceError)) {
                masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;
            }
            return new MasterSetting(true, enabled);
        } catch (RuntimeException e) {
            masterPersistenceError = "master_read_failed";
            Log.e(TAG, "Cannot read Apollo master", e);
            return new MasterSetting(false, false);
        }
    }

    private boolean writeMaster(boolean enabled) {
        try {
            boolean ok = Settings.Global.putInt(
                    getContentResolver(), GLOBAL_MASTER_KEY, enabled ? 1 : 0);
            if (ok) {
                masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;
            } else {
                masterPersistenceError = "master_write_failed";
                lastError = "master_write_failed";
            }
            return ok;
        } catch (RuntimeException e) {
            masterPersistenceError = "master_write_failed";
            lastError = "master_write_failed";
            Log.e(TAG, "Cannot write Apollo master", e);
            return false;
        }
    }

    private void forceMasterOff(String reason) {
        if (writeMaster(false)) {
            Log.w(TAG, reason + ": light/fail-safe forces " + GLOBAL_MASTER_KEY + "=0");
        }
    }

    private String reportedError(boolean directTlcSupported) {
        if (!masterPersistenceError.isEmpty()) return masterPersistenceError;
        if (!BuildConfig.IS_FULL) return ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
        if (!hasWriteCanBusPermission()) {
            return ApolloTlcPolicy.ERROR_WRITE_PERMISSION_MISSING;
        }
        if (!schemaCheckComplete) return "profile_check_pending";
        if (!canBusSchemaMatches) return canBusSchemaError;
        if (!runtimeProfileValid) {
            return lastError.isEmpty() ? "profile_runtime_mismatch" : lastError;
        }
        if (!directTlcSupported) return ApolloTlcPolicy.ERROR_PROFILE_UNSUPPORTED;
        if (!canBusConnected) return ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
        if (!callbackAdded) return "callback_unavailable";
        if (!lastError.isEmpty()) return lastError;
        return ApolloTlcPolicy.directTlcStateError(plcSwitch);
    }

    /** Emits one compact interval summary; hot callbacks never log individually. */
    private void logAndRescheduleMetrics() {
        if (destroyed) return;
        long nowMs = SystemClock.elapsedRealtime();
        long actualWindowMs = metricsWindowStartedAtMs <= 0L
                ? METRICS_INTERVAL_MS : Math.max(0L, nowMs - metricsWindowStartedAtMs);
        metricsWindowStartedAtMs = nowMs;
        long total = callbackTotal.getAndSet(0L);
        long relevant = callbackRelevant.getAndSet(0L);
        long ignored = callbackIgnored.getAndSet(0L);
        long rejected = callbackPostRejected.getAndSet(0L);
        long malformed = callbackMalformed.getAndSet(0L);
        long stale = callbackStale.getAndSet(0L);
        long coalescedQueries = queryCoalesced.getAndSet(0L);
        long coalescedWakes = wakeCoalesced.getAndSet(0L);
        long timedOutBinds = bindTimeouts.getAndSet(0L);
        long maxDispatchUs = workerDispatchMaxUs.getAndSet(0L);
        long descriptors = descriptorCount.getAndSet(0L);
        long descriptorDurationUs = descriptorTotalUs.getAndSet(0L);
        long descriptorLongestUs = descriptorMaxUs.getAndSet(0L);
        StringBuilder transactions = new StringBuilder();
        long transactionEvents = 0L;
        for (int code : TRACKED_TRANSACTIONS) {
            long count = transactionCount.getAndSet(code, 0L);
            long totalUs = transactionTotalUs.getAndSet(code, 0L);
            long maxUs = transactionMaxUs.getAndSet(code, 0L);
            if (count == 0L) continue;
            transactionEvents += count;
            if (transactions.length() > 0) transactions.append(',');
            transactions.append(code)
                    .append(':').append(count)
                    .append('/').append(totalUs / count)
                    .append('/').append(maxUs);
        }
        if (descriptors != 0L) {
            transactionEvents += descriptors;
            if (transactions.length() > 0) transactions.append(',');
            transactions.append("descriptor:").append(descriptors)
                    .append('/').append(descriptorDurationUs / descriptors)
                    .append('/').append(descriptorLongestUs);
        }
        if (total != 0L || transactionEvents != 0L || coalescedQueries != 0L
                || coalescedWakes != 0L || rejected != 0L
                || malformed != 0L || stale != 0L || maxDispatchUs != 0L
                || timedOutBinds != 0L) {
            Log.i(TAG, "metrics window_ms=" + actualWindowMs
                    + " callbacks=" + total
                    + " relevant=" + relevant
                    + " ignored=" + ignored
                    + " malformed=" + malformed
                    + " stale=" + stale
                    + " post_rejected=" + rejected
                    + " query_coalesced=" + coalescedQueries
                    + " wake_coalesced=" + coalescedWakes
                    + " bind_timeouts=" + timedOutBinds
                    + " worker_delay_max_us=" + maxDispatchUs
                    + " tx_code_count_avg_max_us={" + transactions + '}');
        }
        Handler target = handler;
        if (target != null && !destroyed) {
            target.postDelayed(metricsRunnable, METRICS_INTERVAL_MS);
        }
    }

    private void publishState() {
        if (destroyed) return;
        boolean directTlcSupported = isDirectTlcSupported();
        MasterSetting master = readPersistedMaster();
        Intent update = new Intent(ACTION_APOLLO_TLC_UPDATE);
        update.putExtra(EXTRA_CAN_CONNECTED, canBusConnected);
        update.putExtra(EXTRA_PROFILE_SUPPORTED, directTlcSupported);
        update.putExtra(EXTRA_DIRECT_TLC_MODE, directTlcSupported);
        // masterEnabled is meaningful only when masterKnown is true. Never turn a failed
        // Settings.Global read into a known OFF state; the UI keeps emergency OFF available.
        update.putExtra(EXTRA_MASTER_KNOWN, master.known);
        update.putExtra(EXTRA_MASTER_ENABLED,
                ApolloTlcPolicy.reportedMasterEnabled(master.known, master.enabled));
        update.putExtra(EXTRA_PENDING, pending);
        update.putExtra(EXTRA_GEAR, gear);
        update.putExtra(EXTRA_PLC_SWITCH, plcSwitch);
        update.putExtra(EXTRA_PLC_STATUS, plcStatus);
        update.putExtra(EXTRA_ANP_SWITCH, anpSwitch);
        update.putExtra(EXTRA_TLC_CAPABILITY, tlcCapability);
        update.putExtra(EXTRA_PLC_CAPABILITY_SA, plcCapabilitySa);
        update.putExtra(EXTRA_GLA_SWITCH, glaSwitch);
        update.putExtra(EXTRA_GLA_LIGHT_CHANGE_SWITCH, glaLightChangeSwitch);
        update.putExtra(EXTRA_TSR_SWITCH, tsrSwitch);
        update.putExtra(EXTRA_ERROR, reportedError(directTlcSupported));
        update.setPackage(RESTOREMODE_PACKAGE);
        sendBroadcast(update, BIND_PERMISSION);
    }
}
