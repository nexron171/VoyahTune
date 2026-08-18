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
import android.util.Log;

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

    private static final HeadlightCanTransport INSTANCE = new HeadlightCanTransport();

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
    private IBinder canBusBinder;

    private final Runnable rebindRunnable = this::bindCanBus;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                String descriptor = service.getInterfaceDescriptor();
                if (!CANBUS_DESCRIPTOR.equals(descriptor)) {
                    Log.e(TAG, "Unexpected Binder descriptor: " + descriptor);
                    rejectBinding();
                    return;
                }
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "Cannot verify CanBus Binder descriptor", e);
                restartBinding();
                return;
            }
            synchronized (lock) {
                canBusBinder = service;
            }
            mainHandler.removeCallbacks(rebindRunnable);
            Log.i(TAG, "CanBusService connected; headlight TX58 ready");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                canBusBinder = null;
            }
            // Android keeps the binding and reconnects it automatically when the service returns.
            Log.w(TAG, "CanBusService disconnected; waiting for reconnect");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "CanBusService binding died");
            restartBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.e(TAG, "CanBusService returned a null binding");
            restartBinding();
        }
    };

    private HeadlightCanTransport() {}

    /** Starts schema verification and binding once per process. Safe to call from multiple services. */
    static void initialize(Context context) {
        INSTANCE.initializeInternal(context);
    }

    /** Sends LOW_BEAM for true and explicit OUT_LAMP_OFF for false. */
    static boolean send(Context context, boolean headlightsOn) {
        INSTANCE.initializeInternal(context);
        return INSTANCE.sendInternal(HeadlightCanPolicy.commandFor(headlightsOn));
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
        Log.i(TAG, "VehicleState schema verified for LOW_BEAM and OUT_LAMP_OFF");
        bindCanBus();
    }

    private void bindCanBus() {
        final Context context;
        synchronized (lock) {
            if (!schemaReady || connectionRegistered || appContext == null) return;
            context = appContext;
        }
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            synchronized (lock) {
                connectionRegistered = bound;
            }
            Log.i(TAG, "bindService returned " + bound);
            if (!bound) scheduleRebind();
        } catch (RuntimeException e) {
            Log.e(TAG, "CanBus bind failed", e);
            synchronized (lock) {
                connectionRegistered = false;
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
        if (binder == null || ordinal == null || !binder.isBinderAlive()) {
            Log.w(TAG, "TX58 unavailable for " + command.vehicleStateName);
            mainHandler.post(this::bindCanBus);
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
            synchronized (lock) {
                if (canBusBinder == binder) canBusBinder = null;
            }
            mainHandler.post(this::restartBinding);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void rejectBinding() {
        synchronized (lock) {
            canBusBinder = null;
        }
        unbindCurrent();
    }

    private void restartBinding() {
        synchronized (lock) {
            canBusBinder = null;
        }
        unbindCurrent();
        scheduleRebind();
    }

    private void unbindCurrent() {
        final Context context;
        final boolean registered;
        synchronized (lock) {
            context = appContext;
            registered = connectionRegistered;
            connectionRegistered = false;
        }
        if (registered && context != null) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, "CanBus unbind failed", e);
            }
        }
    }

    private void scheduleRebind() {
        mainHandler.removeCallbacks(rebindRunnable);
        mainHandler.postDelayed(rebindRunnable, REBIND_DELAY_MS);
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
