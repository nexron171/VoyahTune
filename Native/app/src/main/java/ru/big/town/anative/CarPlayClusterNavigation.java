package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import ru.big.town.common.CarPlayVehicleProtocol;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread state and one coalescing OEM writer; no blocking Binder work on IPC/UI threads. */
final class CarPlayClusterNavigation {
    private static final String TAG = "CarPlayCluster";
    private static final String PACKAGE = "com.qinggan.cluster";
    private static final String DESCRIPTOR = "com.qinggan.cluster.IInstrumentClusterManagerService";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "CarPlayCluster"));
    private final CarPlayNavigationLease lease = new CarPlayNavigationLease();
    private volatile IBinder binder;
    private volatile CarPlayNavigationLease.Maneuver requested;
    private ServiceConnection connection;
    private boolean busy, ownsOutput, watching, destroying, disposed;
    private CarPlayNavigationLease.Maneuver applied;
    private long nextBind;
    private final Runnable bindTimeout =
            () -> {
                if (connection != null && binder == null) {
                    disconnect();
                    nextBind = SystemClock.elapsedRealtime() + 5000;
                }
            };
    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    pump();
                    if (watching || ownsOutput || busy) main.postDelayed(this, 1000);
                }
            };

    CarPlayClusterNavigation(Context context) {
        this.context = context.getApplicationContext();
    }

    void update(Bundle values) {
        if (destroying) return;
        lease.update(
                values.getBoolean(CarPlayVehicleProtocol.NAV_ACTIVE),
                values.getInt(CarPlayVehicleProtocol.NAV_ICON, -1),
                values.getInt(CarPlayVehicleProtocol.NAV_DISTANCE, -1),
                values.getString(CarPlayVehicleProtocol.NAV_ROAD),
                values.getLong(CarPlayVehicleProtocol.NAV_TIME, -1),
                SystemClock.elapsedRealtime());
        watching = lease.current(SystemClock.elapsedRealtime()) != null;
        main.removeCallbacks(tick);
        tick.run();
    }

    void clear() {
        lease.clear();
        watching = false;
        main.removeCallbacks(tick);
        tick.run();
    }

    void destroy() {
        destroying = true;
        clear();
        main.postDelayed(
                () -> {
                    if (ownsOutput || busy)
                        Log.w(TAG, "Cluster clear was not confirmed before service shutdown");
                    disposed = true;
                    main.removeCallbacks(tick);
                    disconnect();
                    worker.shutdown();
                },
                5000);
    }

    private void pump() {
        if (disposed) return;
        CarPlayNavigationLease.Maneuver wanted = lease.current(SystemClock.elapsedRealtime());
        requested = wanted;
        if (wanted == null) watching = false;
        if (busy) return;
        if (wanted == null && !ownsOutput) {
            disconnect();
            if (destroying) worker.shutdown();
            return;
        }
        if (binder == null) {
            bind();
            return;
        }
        if (Objects.equals(wanted, applied) && wanted != null) return;
        IBinder target = binder;
        busy = true;
        worker.execute(
                () -> {
                    Exception failure = null;
                    boolean attempted = false;
                    try {
                        OemWriter writer = new OemWriter(context, target);
                        if (target == binder && Objects.equals(wanted, requested)) {
                            attempted = true;
                            writer.send(wanted);
                        }
                    } catch (Exception error) {
                        failure = error;
                    }
                    Exception result = failure;
                    boolean mayHaveWritten = attempted;
                    main.post(
                            () -> {
                                busy = false;
                                // A failed Binder call may have reached the service before it
                                // disconnected.
                                ownsOutput |= mayHaveWritten;
                                if (target == binder) {
                                    if (result == null && mayHaveWritten) {
                                        applied = wanted;
                                        ownsOutput = wanted != null;
                                        Log.d(
                                                TAG,
                                                wanted == null
                                                        ? "MAN_STOP sent"
                                                        : "Maneuver sent icon=" + wanted.icon);
                                    } else if (result != null) {
                                        Log.w(TAG, "Cluster write failed", result);
                                        disconnect();
                                        nextBind = SystemClock.elapsedRealtime() + 5000;
                                    }
                                }
                                pump();
                            });
                });
    }

    private void bind() {
        if (connection != null || SystemClock.elapsedRealtime() < nextBind) return;
        ServiceConnection next =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        if (connection != this) return;
                        main.removeCallbacks(bindTimeout);
                        binder = service;
                        applied = null;
                        pump();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        if (connection != this) return;
                        binder = null;
                        applied = null;
                        main.removeCallbacks(bindTimeout);
                        main.postDelayed(bindTimeout, 5000);
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        failed();
                    }

                    @Override
                    public void onNullBinding(ComponentName name) {
                        failed();
                    }

                    private void failed() {
                        if (connection != this) return;
                        disconnect();
                        nextBind = SystemClock.elapsedRealtime() + 5000;
                    }
                };
        connection = next;
        try {
            Intent intent =
                    new Intent("com.qinggan.cluster.InstrumentClusterService")
                            .setClassName(PACKAGE, PACKAGE + ".service.InstrumentClusterService");
            if (context.bindService(intent, next, Context.BIND_AUTO_CREATE)) {
                main.postDelayed(bindTimeout, 5000);
            } else {
                connection = null;
                nextBind = SystemClock.elapsedRealtime() + 5000;
            }
        } catch (RuntimeException error) {
            connection = null;
            nextBind = SystemClock.elapsedRealtime() + 5000;
            Log.w(TAG, "Cluster bind unavailable", error);
        }
    }

    private void disconnect() {
        main.removeCallbacks(bindTimeout);
        ServiceConnection old = connection;
        connection = null;
        binder = null;
        applied = null;
        if (old != null) {
            try {
                context.unbindService(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Use the installed system APK's Parcelable/proxy instead of guessing transaction layouts. */
    private static final class OemWriter {
        private final Object proxy;
        private final Class<?> info, guide;
        private final Method update;

        OemWriter(Context context, IBinder binder) throws Exception {
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
                throw new IllegalStateException("Wrong cluster interface");
            ApplicationInfo application =
                    context.getPackageManager().getApplicationInfo(PACKAGE, 0);
            if ((application.flags
                            & (ApplicationInfo.FLAG_SYSTEM
                                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                    == 0) throw new IllegalStateException("Cluster must be a system package");
            ClassLoader loader =
                    context.createPackageContext(
                                    PACKAGE,
                                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)
                            .getClassLoader();
            Class<?> contract = loader.loadClass(DESCRIPTOR);
            proxy =
                    loader.loadClass(DESCRIPTOR + "$Stub")
                            .getMethod("asInterface", IBinder.class)
                            .invoke(null, binder);
            info = loader.loadClass("com.qinggan.cluster.info.NaviInfo");
            guide = loader.loadClass("com.qinggan.cluster.info.GuideState");
            update = contract.getMethod("updateNaviInfo", info);
        }

        void send(CarPlayNavigationLease.Maneuver maneuver) throws Exception {
            Object data = info.getConstructor().newInstance();
            info.getMethod("setGuideState", guide)
                    .invoke(
                            data,
                            guide.getField(maneuver == null ? "MAN_STOP" : "GUIDING").get(null));
            info.getMethod("setCurrentRoadName", String.class).invoke(data, "");
            info.getMethod("setTurnRoadName", String.class)
                    .invoke(data, maneuver == null ? "" : maneuver.road);
            info.getMethod("setDestName", String.class).invoke(data, "");
            info.getMethod("setDisplay", boolean.class).invoke(data, maneuver != null);
            info.getMethod("setTbtIconId", int.class)
                    .invoke(data, maneuver == null ? 0 : maneuver.icon);
            info.getMethod("setNextDistance", int.class)
                    .invoke(data, maneuver == null ? 0 : maneuver.distance);
            update.invoke(proxy, data);
        }
    }
}
