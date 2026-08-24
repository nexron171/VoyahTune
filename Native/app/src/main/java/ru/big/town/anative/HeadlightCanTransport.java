package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.PathClassLoader;

/**
 * Minimal OEM CanBus Binder transport used only for exterior-headlight mode commands.
 *
 * <p>The stock VehicleSetting app sends {@code VehicleState} through ICanBusService TX58. The
 * service then constructs the firmware-specific 0x1F frame from current cached vehicle values.
 * Keeping that construction in the OEM service avoids replaying stale screen, language, fragrance
 * and ambient-light fields that share the same raw messages.</p>
 */
final class HeadlightCanTransport {
    private static final String TAG = "$$$ HeadlightCanTransport $$$";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final String WRITE_CANBUS_PERMISSION = "com.qinggan.permission.WRITE_CANBUS";
    private static final int TX_SET_VEHICLE_STATE = 58;
    private static final long REBIND_DELAY_MS = 5_000L;
    private static final int MAX_REBIND_RETRIES_PER_EVENT = 1;

    private static final HeadlightCanTransport INSTANCE = new HeadlightCanTransport();

    interface ReadyListener {
        void onHeadlightTransportReady();
    }

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService schemaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HeadlightCanSchema");
        thread.setDaemon(true);
        return thread;
    });
    private final EnumMap<HeadlightCanPolicy.Command, Integer> ordinals =
            new EnumMap<>(HeadlightCanPolicy.Command.class);

    private Context appContext;
    private boolean initialized;
    private boolean schemaReady;
    private boolean connectionRegistered;
    private BindConnection connection;
    private long nextBindingGeneration;
    private long activeBindingGeneration;
    private IBinder canBusBinder;
    private long connectionRequestedAtElapsed;
    private final EventRetryBudget rebindRetryBudget =
            new EventRetryBudget(MAX_REBIND_RETRIES_PER_EVENT);
    private long recoveryScope;
    private long scheduledRecoveryScope;
    private boolean recoveryInProgress;
    private BindConnection scheduledStuckConnection;
    private long scheduledStuckGeneration;
    private long scheduledStuckRequestedAt;
    private WeakReference<ReadyListener> readyListener = new WeakReference<>(null);

    private final Runnable rebindRunnable = () -> {
        long scope = scheduledRecoveryScope;
        if (rebindRetryBudget.isCurrent(scope)) bindCanBus();
    };
    private final Runnable stuckBindRunnable = () -> {
        final BindConnection candidate;
        final long generation;
        final long requestedAt;
        synchronized (lock) {
            candidate = scheduledStuckConnection;
            generation = scheduledStuckGeneration;
            requestedAt = scheduledStuckRequestedAt;
            scheduledStuckConnection = null;
            scheduledStuckGeneration = 0L;
            scheduledStuckRequestedAt = 0L;
        }
        restartStuckPendingBind(candidate, generation, requestedAt);
    };

    private final class BindConnection implements ServiceConnection {
        private final long generation;

        BindConnection(long generation) {
            this.generation = generation;
        }

        private boolean isCurrent() {
            synchronized (lock) {
                return connection == this && activeBindingGeneration == generation;
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!isCurrent()) return;
            try {
                String descriptor = service.getInterfaceDescriptor();
                if (!CANBUS_DESCRIPTOR.equals(descriptor)) {
                    Log.e(TAG, "Unexpected Binder descriptor: " + descriptor);
                    restartBindingAfterFailure(this);
                    return;
                }
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "Cannot verify CanBus Binder descriptor", e);
                restartBindingAfterFailure(this);
                return;
            }
            synchronized (lock) {
                if (connection != this || activeBindingGeneration != generation) return;
                canBusBinder = service;
                connectionRequestedAtElapsed = 0L;
                recoveryInProgress = false;
                scheduledStuckConnection = null;
                scheduledStuckGeneration = 0L;
                scheduledStuckRequestedAt = 0L;
            }
            mainHandler.removeCallbacks(rebindRunnable);
            mainHandler.removeCallbacks(stuckBindRunnable);
            Log.i(TAG, "CanBusService connected; headlight TX58 ready");
            notifyReadyListener();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!isCurrent()) return;
            openRecoveryScope();
            final long requestedAt = SystemClock.elapsedRealtime();
            synchronized (lock) {
                canBusBinder = null;
                connectionRequestedAtElapsed = requestedAt;
            }
            // Android keeps the binding and reconnects it automatically when the service returns.
            Log.w(TAG, "CanBusService disconnected; waiting for reconnect");
            scheduleStuckBindCheck(this, generation, requestedAt, REBIND_DELAY_MS);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (!isCurrent()) return;
            Log.w(TAG, "CanBusService binding died");
            restartBindingForEvent(this);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (!isCurrent()) return;
            Log.e(TAG, "CanBusService returned a null binding");
            restartBindingAfterFailure(this);
        }
    }

    private HeadlightCanTransport() {}

    /** Starts schema verification and binding once per process. Safe to call from multiple services. */
    static void initialize(Context context) {
        INSTANCE.initializeInternal(context);
    }

    /** Rearms one finite connection recovery cycle after a real wake/light decision. */
    static void requestRecovery(Context context) {
        INSTANCE.initializeInternal(context);
        INSTANCE.mainHandler.post(INSTANCE::recoverForExternalEvent);
    }

    /** Waits for the current finite recovery cycle without opening another retry budget. */
    static void awaitReadyAfterFailure(Context context) {
        INSTANCE.initializeInternal(context);
        INSTANCE.mainHandler.post(INSTANCE::recoverAfterFailure);
    }

    static void setReadyListener(ReadyListener listener) {
        synchronized (INSTANCE.lock) {
            INSTANCE.readyListener = new WeakReference<>(listener);
        }
    }

    static void clearReadyListener(ReadyListener listener) {
        synchronized (INSTANCE.lock) {
            ReadyListener current = INSTANCE.readyListener.get();
            if (current == listener) INSTANCE.readyListener = new WeakReference<>(null);
        }
    }

    /** Sends LOW_BEAM for true and explicit OUT_LAMP_OFF for false. */
    static boolean send(Context context, boolean headlightsOn) {
        INSTANCE.initializeInternal(context);
        return INSTANCE.sendInternal(HeadlightCanPolicy.commandFor(headlightsOn));
    }

    /** Sends LOW_BEAM for true and OEM AUTO_LAMP_SWITCH for false. */
    static boolean sendAutoPair(Context context, boolean lowBeam) {
        INSTANCE.initializeInternal(context);
        return INSTANCE.sendInternal(HeadlightCanPolicy.commandForAutoPair(lowBeam));
    }

    private void initializeInternal(Context context) {
        if (context == null) return;
        synchronized (lock) {
            if (initialized) return;
            initialized = true;
            appContext = context.getApplicationContext();
        }
        if (appContext.checkSelfPermission(WRITE_CANBUS_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "WRITE_CANBUS permission missing; headlight commands disabled");
            return;
        }
        schemaExecutor.execute(() -> {
            SchemaResult result = resolveSchema();
            mainHandler.post(() -> applySchema(result));
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SchemaResult resolveSchema() {
        try {
            ApplicationInfo info = appContext.getPackageManager()
                    .getApplicationInfo(CANBUS_PACKAGE, 0);
            ClassLoader loader = new PathClassLoader(info.sourceDir, appContext.getClassLoader());
            Class enumClass = Class.forName("com.qinggan.canbus.VehicleState", true, loader);
            if (!enumClass.isEnum()) return SchemaResult.failed("VehicleState is not an enum");
            Method getValue = enumClass.getMethod("getValue");
            EnumMap<HeadlightCanPolicy.Command, Integer> resolved =
                    new EnumMap<>(HeadlightCanPolicy.Command.class);
            for (HeadlightCanPolicy.Command command : HeadlightCanPolicy.Command.values()) {
                Enum value = Enum.valueOf(enumClass, command.vehicleStateName);
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer)
                        || ((Integer) stableId).intValue() != command.stableId) {
                    return SchemaResult.failed("VehicleState id mismatch for "
                            + command.vehicleStateName);
                }
                resolved.put(command, value.ordinal());
            }
            return SchemaResult.success(resolved);
        } catch (PackageManager.NameNotFoundException e) {
            return SchemaResult.failed("CanBusService APK not found");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Cannot resolve VehicleState schema", e);
            return SchemaResult.failed("VehicleState schema unavailable");
        }
    }

    private void applySchema(SchemaResult result) {
        synchronized (lock) {
            ordinals.clear();
            if (result.success) ordinals.putAll(result.ordinals);
            schemaReady = result.success;
        }
        if (!result.success) {
            Log.e(TAG, "Headlight Binder disabled: " + result.error);
            return;
        }
        Log.i(TAG, "VehicleState schema verified for LOW_BEAM, OUT_LAMP_OFF and AUTO_LAMP_SWITCH");
        openRecoveryScope();
        bindCanBus();
    }

    private void bindCanBus() {
        final Context context;
        final BindConnection candidate;
        synchronized (lock) {
            if (!schemaReady || connection != null || appContext == null) return;
            context = appContext;
            candidate = new BindConnection(++nextBindingGeneration);
            connection = candidate;
            activeBindingGeneration = candidate.generation;
        }
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            boolean bound = context.bindService(intent, candidate, Context.BIND_AUTO_CREATE);
            final boolean stale;
            synchronized (lock) {
                stale = connection != candidate
                        || activeBindingGeneration != candidate.generation;
                if (!stale) {
                    connectionRegistered = bound;
                    connectionRequestedAtElapsed = bound ? SystemClock.elapsedRealtime() : 0L;
                    if (!bound) {
                        connection = null;
                        activeBindingGeneration = 0L;
                    }
                }
            }
            if (stale) {
                if (bound) safeUnbind(context, candidate);
                return;
            }
            Log.i(TAG, "bindService returned " + bound);
            if (!bound) {
                scheduleRebind();
            } else {
                final long requestedAt;
                synchronized (lock) {
                    requestedAt = connection == candidate
                            && activeBindingGeneration == candidate.generation
                            ? connectionRequestedAtElapsed : 0L;
                }
                if (requestedAt > 0L) {
                    scheduleStuckBindCheck(
                            candidate, candidate.generation, requestedAt, REBIND_DELAY_MS);
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "CanBus bind failed", e);
            synchronized (lock) {
                if (connection == candidate
                        && activeBindingGeneration == candidate.generation) {
                    connectionRegistered = false;
                    connection = null;
                    activeBindingGeneration = 0L;
                    connectionRequestedAtElapsed = 0L;
                }
            }
            scheduleRebind();
        }
    }

    private boolean sendInternal(HeadlightCanPolicy.Command command) {
        final IBinder binder;
        final Integer ordinal;
        synchronized (lock) {
            binder = canBusBinder;
            ordinal = ordinals.get(command);
        }
        boolean binderAlive = binder != null && binder.isBinderAlive();
        if (!binderAlive || ordinal == null) {
            Log.w(TAG, "TX58 unavailable for " + command.vehicleStateName);
            if (!binderAlive) markBindingPendingAfterFailure(binder);
            mainHandler.post(this::recoverAfterFailure);
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(1); // VehicleState object is present
            data.writeInt(ordinal.intValue());
            data.writeInt(command.stableId);
            data.writeInt(HeadlightCanPolicy.ACTIVATE);
            if (!CanSender.beginFrameAttemptForCurrentGuard()) return false;
            if (!binder.transact(TX_SET_VEHICLE_STATE, data, reply, 0)) {
                Log.e(TAG, "TX58 rejected for " + command.vehicleStateName);
                return false;
            }
            reply.readException();
            Log.i(TAG, "TX58 " + command.vehicleStateName + " state="
                    + HeadlightCanPolicy.ACTIVATE);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX58 failed for " + command.vehicleStateName, e);
            markBindingPendingAfterFailure(binder);
            mainHandler.post(this::recoverAfterFailure);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Starts the grace age for a registered connection whose usable Binder just failed. */
    private void markBindingPendingAfterFailure(IBinder failedBinder) {
        synchronized (lock) {
            if (canBusBinder != failedBinder || !connectionRegistered || connection == null) {
                return;
            }
            canBusBinder = null;
            if (connectionRequestedAtElapsed <= 0L) {
                connectionRequestedAtElapsed = SystemClock.elapsedRealtime();
            }
        }
    }

    private void restartBindingForEvent(BindConnection failed) {
        if (!failed.isCurrent()) return;
        openRecoveryScope();
        unbindCurrent();
        bindCanBus();
    }

    private void restartBindingAfterFailure(BindConnection failed) {
        if (!failed.isCurrent()) return;
        unbindCurrent();
        scheduleRebind();
    }

    private void recoverForExternalEvent() {
        final boolean ready;
        final boolean registered;
        final IBinder binder;
        final BindConnection pendingConnection;
        final long pendingGeneration;
        final long requestedAt;
        synchronized (lock) {
            ready = schemaReady;
            registered = connectionRegistered;
            binder = canBusBinder;
            pendingConnection = connection;
            pendingGeneration = activeBindingGeneration;
            requestedAt = connectionRequestedAtElapsed;
        }
        if (!ready || (binder != null && binder.isBinderAlive())) return;
        openRecoveryScope();
        if (registered) {
            restartStuckPendingBind(
                    pendingConnection, pendingGeneration, requestedAt);
            return;
        }
        bindCanBus();
    }

    private void recoverAfterFailure() {
        final boolean ready;
        final boolean registered;
        final IBinder binder;
        final BindConnection pendingConnection;
        final long pendingGeneration;
        final long requestedAt;
        final boolean alreadyRecovering;
        synchronized (lock) {
            ready = schemaReady;
            registered = connectionRegistered;
            binder = canBusBinder;
            pendingConnection = connection;
            pendingGeneration = activeBindingGeneration;
            requestedAt = connectionRequestedAtElapsed;
            alreadyRecovering = recoveryInProgress;
        }
        if (!ready) return;
        if (binder != null && binder.isBinderAlive()) {
            notifyReadyListener();
            return;
        }
        if (!alreadyRecovering) openRecoveryScope();
        if (registered) {
            restartStuckPendingBind(
                    pendingConnection, pendingGeneration, requestedAt);
            return;
        }
        if (alreadyRecovering) return;
        bindCanBus();
    }

    /**
     * Replaces a bindService(true) request only after its finite, event-scoped grace interval.
     * The actual replacement consumes the already-open retry budget; the named one-shot never
     * opens or renews a recovery scope.
     */
    private void restartStuckPendingBind(BindConnection pendingConnection,
                                         long pendingGeneration, long requestedAt) {
        if (pendingConnection == null || pendingGeneration <= 0L || requestedAt <= 0L) {
            return;
        }
        long remaining = requestedAt + REBIND_DELAY_MS - SystemClock.elapsedRealtime();
        if (remaining > 0L) {
            scheduleStuckBindCheck(
                    pendingConnection, pendingGeneration, requestedAt, remaining);
            return;
        }
        final long scope;
        synchronized (lock) {
            if (connection != pendingConnection
                    || activeBindingGeneration != pendingGeneration
                    || !connectionRegistered || canBusBinder != null
                    || connectionRequestedAtElapsed != requestedAt) {
                return;
            }
            scope = recoveryScope;
        }
        if (!rebindRetryBudget.claim(scope)) {
            synchronized (lock) {
                if (recoveryScope == scope) recoveryInProgress = false;
            }
            Log.w(TAG, "Stuck CanBus bind retry exhausted; waiting for next wake/light event");
            return;
        }

        final Context context;
        synchronized (lock) {
            if (connection != pendingConnection
                    || activeBindingGeneration != pendingGeneration
                    || !connectionRegistered || canBusBinder != null
                    || connectionRequestedAtElapsed != requestedAt) {
                return;
            }
            context = appContext;
            connectionRegistered = false;
            connection = null;
            activeBindingGeneration = 0L;
            connectionRequestedAtElapsed = 0L;
        }
        if (context != null) safeUnbind(context, pendingConnection);
        Log.w(TAG, "Replacing stuck CanBus bind generation=" + pendingGeneration);
        bindCanBus();
    }

    private void scheduleStuckBindCheck(BindConnection candidate, long generation,
                                        long requestedAt, long delayMs) {
        synchronized (lock) {
            if (connection != candidate || activeBindingGeneration != generation
                    || !connectionRegistered || canBusBinder != null
                    || connectionRequestedAtElapsed != requestedAt) {
                return;
            }
            scheduledStuckConnection = candidate;
            scheduledStuckGeneration = generation;
            scheduledStuckRequestedAt = requestedAt;
        }
        mainHandler.removeCallbacks(stuckBindRunnable);
        mainHandler.postDelayed(stuckBindRunnable, Math.max(1L, delayMs));
    }

    private void openRecoveryScope() {
        synchronized (lock) {
            long scope = ++recoveryScope;
            rebindRetryBudget.reset(scope);
            scheduledRecoveryScope = scope;
            recoveryInProgress = true;
        }
        mainHandler.removeCallbacks(rebindRunnable);
        mainHandler.removeCallbacks(stuckBindRunnable);
    }

    private void unbindCurrent() {
        final Context context;
        final boolean registered;
        final BindConnection current;
        synchronized (lock) {
            context = appContext;
            registered = connectionRegistered;
            current = connection;
            connectionRegistered = false;
            connection = null;
            activeBindingGeneration = 0L;
            canBusBinder = null;
            connectionRequestedAtElapsed = 0L;
            scheduledStuckConnection = null;
            scheduledStuckGeneration = 0L;
            scheduledStuckRequestedAt = 0L;
        }
        mainHandler.removeCallbacks(stuckBindRunnable);
        if (registered && context != null && current != null) safeUnbind(context, current);
    }

    private void scheduleRebind() {
        long scope = recoveryScope;
        if (!rebindRetryBudget.claim(scope)) {
            synchronized (lock) {
                if (recoveryScope == scope) recoveryInProgress = false;
            }
            Log.w(TAG, "CanBus bind retry exhausted; waiting for next wake/light event");
            return;
        }
        scheduledRecoveryScope = scope;
        mainHandler.removeCallbacks(rebindRunnable);
        mainHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS);
    }

    private void notifyReadyListener() {
        final ReadyListener listener;
        synchronized (lock) {
            listener = readyListener.get();
        }
        if (listener == null) return;
        try {
            listener.onHeadlightTransportReady();
        } catch (RuntimeException e) {
            Log.w(TAG, "Headlight readiness callback failed", e);
        }
    }

    private static void safeUnbind(Context context, ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (RuntimeException e) {
            Log.w(TAG, "CanBus unbind failed", e);
        }
    }

    private static final class SchemaResult {
        final boolean success;
        final EnumMap<HeadlightCanPolicy.Command, Integer> ordinals;
        final String error;

        private SchemaResult(boolean success,
                             EnumMap<HeadlightCanPolicy.Command, Integer> ordinals,
                             String error) {
            this.success = success;
            this.ordinals = ordinals;
            this.error = error;
        }

        static SchemaResult success(EnumMap<HeadlightCanPolicy.Command, Integer> ordinals) {
            return new SchemaResult(true, ordinals, null);
        }

        static SchemaResult failed(String error) {
            return new SchemaResult(false,
                    new EnumMap<>(HeadlightCanPolicy.Command.class), error);
        }
    }
}
