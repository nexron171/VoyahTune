package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.PathClassLoader;

/** OEM TX77 transport for complete drive-mode VehicleState bundles. */
final class DriveModeCanTransport {
    private static final String TAG = "$$$ DriveModeCanTransport $$$";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final String WRITE_CANBUS_PERMISSION = "com.qinggan.permission.WRITE_CANBUS";
    private static final int TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE = 77;
    private static final long REBIND_DELAY_MS = 5_000L;

    private static final DriveModeCanTransport INSTANCE = new DriveModeCanTransport();

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService schemaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DriveModeCanSchema");
        thread.setDaemon(true);
        return thread;
    });

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
            Log.i(TAG, "CanBusService connected; drive-mode TX77 ready");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                canBusBinder = null;
            }
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

    private DriveModeCanTransport() {}

    static void initialize(Context context) {
        INSTANCE.initializeInternal(context);
    }

    static boolean send(Context context, String mode) {
        if (CanSender.isDebugMode()) return emulate(mode);
        INSTANCE.initializeInternal(context);
        return INSTANCE.sendInternal(mode);
    }

    static boolean send(String mode) {
        if (CanSender.isDebugMode()) return emulate(mode);
        return INSTANCE.sendInternal(mode);
    }

    private static boolean emulate(String mode) {
        boolean supported = DriveModeCanPolicy.isSupported(mode);
        Log.i(TAG, "EMULATE OEM TX77 drive mode=" + mode + " supported=" + supported);
        return supported;
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
            Log.e(TAG, "WRITE_CANBUS permission missing; drive-mode commands disabled");
            return;
        }
        schemaExecutor.execute(() -> {
            String error = verifySchema();
            mainHandler.post(() -> applySchema(error));
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String verifySchema() {
        try {
            ApplicationInfo info = appContext.getPackageManager()
                    .getApplicationInfo(CANBUS_PACKAGE, 0);
            ClassLoader loader = new PathClassLoader(info.sourceDir, appContext.getClassLoader());
            Class enumClass = Class.forName("com.qinggan.canbus.VehicleState", true, loader);
            if (!enumClass.isEnum()) return "VehicleState is not an enum";
            Method getValue = enumClass.getMethod("getValue");
            for (DriveModeCanPolicy.VehicleStateKey key
                    : DriveModeCanPolicy.VehicleStateKey.values()) {
                Enum value = Enum.valueOf(enumClass, key.name());
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer)
                        || ((Integer) stableId).intValue() != key.stableId) {
                    return "VehicleState id mismatch for " + key.name();
                }
            }
            return null;
        } catch (PackageManager.NameNotFoundException e) {
            return "CanBusService APK not found";
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Cannot resolve drive-mode VehicleState schema", e);
            return "VehicleState schema unavailable";
        }
    }

    private void applySchema(String error) {
        synchronized (lock) {
            schemaReady = error == null;
        }
        if (error != null) {
            Log.e(TAG, "Drive-mode Binder disabled: " + error);
            return;
        }
        Log.i(TAG, "Drive-mode VehicleState schema verified");
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

    private boolean sendInternal(String mode) {
        if (!DriveModeCanPolicy.isSupported(mode)) {
            Log.e(TAG, "Unsupported drive mode: " + mode);
            return false;
        }

        final Context context;
        final IBinder binder;
        synchronized (lock) {
            context = appContext;
            binder = canBusBinder;
        }
        if (context == null || binder == null || !binder.isBinderAlive()) {
            Log.w(TAG, "TX77 unavailable for drive mode " + mode);
            mainHandler.post(this::bindCanBus);
            return false;
        }

        DriveModeCanPolicy.IndividualProfile individual = "INDIVIDUAL".equals(mode)
                ? OemIndividualDriveProfileReader.read(context) : null;
        DriveModeCanPolicy.Plan plan = DriveModeCanPolicy.planFor(mode, individual);
        if (plan == null) {
            Log.e(TAG, "Cannot build safe OEM plan for drive mode " + mode);
            return false;
        }

        Bundle vehicleBundle = new Bundle();
        for (Map.Entry<DriveModeCanPolicy.VehicleStateKey, Integer> entry
                : plan.values().entrySet()) {
            vehicleBundle.putInt(entry.getKey().name(), entry.getValue());
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(0); // air-condition bundle is null
            data.writeInt(1); // vehicle bundle is present
            vehicleBundle.writeToParcel(data, 0);
            if (!binder.transact(TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE, data, reply, 0)) {
                Log.e(TAG, "TX77 rejected for drive mode " + mode);
                return false;
            }
            reply.readException();
            int result = reply.readInt();
            if (result != 0) {
                Log.e(TAG, "TX77 returned " + result + " for drive mode " + mode);
                return false;
            }
            Log.i(TAG, "TX77 accepted drive mode " + mode + " states=" + vehicleBundle);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX77 failed for drive mode " + mode, e);
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
}
