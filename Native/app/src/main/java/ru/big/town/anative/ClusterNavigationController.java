package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.CarPlayVehicleProtocol;
import ru.big.town.common.ClusterNavigationSettings;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread state and one coalescing OEM writer; no blocking Binder work on IPC/UI threads. */
final class ClusterNavigationController {
    private static final String TAG = "ClusterNavigation";
    private static ClusterNavigationController instance;
    private static final String PACKAGE = "com.qinggan.cluster";
    private static final String ACTION = "com.qinggan.cluster.InstrumentClusterService";
    private static final String SERVICE_CLASS = PACKAGE + ".service.InstrumentClusterService";
    private static final String DESCRIPTOR = "com.qinggan.cluster.IInstrumentClusterManagerService";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "ClusterNavigation"));
    private final ClusterNavigationSources sources = new ClusterNavigationSources();
    private volatile IBinder binder;
    private volatile ClusterNavigationLease.Maneuver requested;
    private ServiceConnection connection;
    private boolean transliteration;
    private boolean showStreet;
    private boolean busy;
    private boolean ownsOutput;
    private boolean watching;
    private ClusterNavigationLease.Maneuver applied;
    private Boolean clusterConnected;
    private long nextBind;
    private final ContentObserver settingsObserver =
            new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshEnabled();
                }
            };
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
                    if (watching || ownsOutput || busy) {
                        main.postDelayed(this, 1000);
                    }
                }
            };

    static ClusterNavigationController get(Context context) {
        if (instance == null) {
            instance = new ClusterNavigationController(context);
        }
        return instance;
    }

    private ClusterNavigationController(Context context) {
        this.context = context.getApplicationContext();
        Log.i(TAG, "platform=" + Build.PRODUCT + " presentation=" + property("ro.qg.presentation"));
        this.context
                .getContentResolver()
                .registerContentObserver(
                        Settings.Global.getUriFor(ClusterNavigationSettings.GLOBAL_ENABLED),
                        false,
                        settingsObserver);
        this.context
                .getContentResolver()
                .registerContentObserver(
                        Settings.Global.getUriFor(ClusterNavigationSettings.GLOBAL_TRANSLITERATION),
                        false,
                        settingsObserver);
        this.context
                .getContentResolver()
                .registerContentObserver(
                        Settings.Global.getUriFor(ClusterNavigationSettings.GLOBAL_SHOW_STREET),
                        false,
                        settingsObserver);
        refreshEnabled();
    }

    private static String property(String name) {
        try {
            Method get =
                    Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            Object value = get.invoke(null, name);
            return value instanceof String ? (String) value : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    private void refreshEnabled() {
        boolean enabled =
                Settings.Global.getInt(
                                context.getContentResolver(),
                                ClusterNavigationSettings.GLOBAL_ENABLED,
                                ClusterNavigationSettings.DEFAULT_ENABLED ? 1 : 0)
                        != 0;
        transliteration =
                Settings.Global.getInt(
                                context.getContentResolver(),
                                ClusterNavigationSettings.GLOBAL_TRANSLITERATION,
                                ClusterNavigationSettings.DEFAULT_TRANSLITERATION ? 1 : 0)
                        != 0;
        showStreet =
                Settings.Global.getInt(
                                context.getContentResolver(),
                                ClusterNavigationSettings.GLOBAL_SHOW_STREET,
                                ClusterNavigationSettings.DEFAULT_SHOW_STREET ? 1 : 0)
                        != 0;
        sources.setEnabled(enabled, SystemClock.elapsedRealtime());
        refreshOutput();
    }

    void update(ClusterNavigationSources.Source source, Bundle values) {
        sources.update(
                source,
                values.getBoolean(CarPlayVehicleProtocol.NAV_ACTIVE),
                values.getInt(CarPlayVehicleProtocol.NAV_ICON, -1),
                values.getInt(CarPlayVehicleProtocol.NAV_DISTANCE, -1),
                values.getString(CarPlayVehicleProtocol.NAV_ROAD),
                values.getLong(CarPlayVehicleProtocol.NAV_TIME, -1),
                SystemClock.elapsedRealtime());
        refreshOutput();
    }

    void clear(ClusterNavigationSources.Source source) {
        sources.clear(source);
        refreshOutput();
    }

    private void refreshOutput() {
        watching = sources.current(SystemClock.elapsedRealtime()) != null;
        main.removeCallbacks(tick);
        tick.run();
    }

    private void pump() {
        ClusterNavigationLease.Maneuver wanted =
                formatManeuver(sources.current(SystemClock.elapsedRealtime()));
        requested = wanted;
        if (wanted == null) {
            watching = false;
        }
        if (busy) {
            return;
        }
        if (wanted == null && !ownsOutput) {
            disconnect();
            return;
        }
        if (binder == null) {
            bind();
            return;
        }
        if (Objects.equals(wanted, applied) && wanted != null) {
            return;
        }
        IBinder target = binder;
        busy = true;
        worker.execute(
                () -> {
                    Exception failure = null;
                    boolean attempted = false;
                    Boolean connected = null;
                    try {
                        OemWriter writer = new OemWriter(context, target);
                        connected = writer.connected();
                        if (target == binder && Objects.equals(wanted, requested)) {
                            attempted = true;
                            writer.send(wanted);
                        }
                    } catch (Exception error) {
                        failure = error;
                    }
                    Exception result = failure;
                    boolean mayHaveWritten = attempted;
                    Boolean link = connected;
                    main.post(
                            () -> {
                                busy = false;
                                // A failed Binder call may have reached the service before it
                                // disconnected.
                                ownsOutput |= mayHaveWritten;
                                if (link != null && !link.equals(clusterConnected)) {
                                    clusterConnected = link;
                                    if (link) {
                                        Log.i(TAG, "Cluster link with the IPK is up");
                                    } else {
                                        Log.w(TAG, "No IPK link reported; the maneuver may be dropped");
                                    }
                                }
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

    private ClusterNavigationLease.Maneuver formatManeuver(
            ClusterNavigationLease.Maneuver maneuver) {
        if (maneuver == null) {
            return null;
        }
        return new ClusterNavigationLease.Maneuver(
                maneuver.icon,
                maneuver.distance,
                ClusterNavigationText.format(maneuver.road, transliteration, showStreet));
    }

    private void bind() {
        if (connection != null || SystemClock.elapsedRealtime() < nextBind) {
            return;
        }
        ServiceConnection next =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        if (connection != this) {
                            return;
                        }
                        main.removeCallbacks(bindTimeout);
                        binder = service;
                        applied = null;
                        pump();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        if (connection != this) {
                            return;
                        }
                        binder = null;
                        applied = null;
                        main.removeCallbacks(bindTimeout);
                        main.postDelayed(bindTimeout, 5000);
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        Log.w(TAG, "Cluster binding died: " + name);
                        failed();
                    }

                    @Override
                    public void onNullBinding(ComponentName name) {
                        // The service only publishes its binder when ro.qg.presentation is
                        // VirtualDisplay; otherwise Android reports a null binding.
                        Log.w(TAG, "Cluster returned a null binder: " + name);
                        failed();
                    }

                    private void failed() {
                        if (connection != this) {
                            return;
                        }
                        disconnect();
                        nextBind = SystemClock.elapsedRealtime() + 5000;
                    }
                };
        connection = next;
        try {
            // The OEM manager binds by action; the class name is only a fallback for builds
            // whose intent filter is missing.
            Intent intent = new Intent(ACTION).setPackage(PACKAGE);
            boolean byAction = context.getPackageManager().resolveService(intent, 0) != null;
            if (!byAction) {
                intent = new Intent(ACTION).setClassName(PACKAGE, SERVICE_CLASS);
            }
            Log.i(TAG, "Binding cluster service by " + (byAction ? "action" : "class"));
            if (context.bindService(intent, next, Context.BIND_AUTO_CREATE)) {
                main.postDelayed(bindTimeout, 5000);
            } else {
                connection = null;
                nextBind = SystemClock.elapsedRealtime() + 5000;
                Log.w(TAG, "Cluster service rejected the binding request");
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
        private final Class<?> info;
        private final Class<?> guide;
        private final Method update;
        private final Method connect;

        OemWriter(Context context, IBinder binder) throws Exception {
            String descriptor = binder.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(descriptor)) {
                throw new IllegalStateException("Wrong cluster interface: " + descriptor);
            }
            ApplicationInfo application =
                    context.getPackageManager().getApplicationInfo(PACKAGE, 0);
            if ((application.flags
                            & (ApplicationInfo.FLAG_SYSTEM
                                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                    == 0) {
                throw new IllegalStateException("Cluster must be a system package");
            }
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
            Method clusterConnect = null;
            try {
                clusterConnect = contract.getMethod("getClusterConnect");
            } catch (NoSuchMethodException absent) {
                // Older builds may not expose the IPK connection state.
            }
            connect = clusterConnect;
        }

        /** Reports the OEM link to the cluster display; null when the build cannot answer. */
        Boolean connected() {
            if (connect == null) {
                return null;
            }
            try {
                Object value = connect.invoke(proxy);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Exception unavailable) {
                return null;
            }
        }

        void send(ClusterNavigationLease.Maneuver maneuver) throws Exception {
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
