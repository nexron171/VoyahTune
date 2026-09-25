package ru.big.town.anative;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Uses the installed vendor Java implementation, never a guessed Binder transaction or raw CAN. */
final class SeatOemTransport implements SeatCommandSender.Transport {
    private static final SeatOemTransport INSTANCE = new SeatOemTransport();
    private final Object connection = new Object();
    private Context context;
    private Class<?> managerClass, stateClass, listenerClass;
    private Method setter;
    private Object manager, initListener, selectedState;
    private String selectedField;
    private boolean connected;
    private long deadline;

    static SeatOemTransport get(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.context == null) INSTANCE.context = context.getApplicationContext();
        }
        return INSTANCE;
    }

    // Calls are serialized by ApplyEngine's independent user-command queue.
    @Override public void prepare(String field, long deadlineMs) throws Exception {
        deadline = deadlineMs;
        selectedState = null;
        selectedField = null;
        if (context.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS") != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("WRITE_CANBUS not granted");
        }
        if (SystemClock.elapsedRealtime() >= deadline) throw new TimeoutException();
        if (managerClass == null) loadApi();
        try {
            selectedState = stateClass.getField(field).get(null);
            if (!stateClass.isInstance(selectedState)) throw new UnsupportedOperationException(field);
            selectedField = field;
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(field, e);
        }
        if (manager == null) {
            // Retain one application-scoped listener: OEM has no supported removal API for it.
            // It carries neither an Activity nor a voice session / command callback.
            if (initListener == null) initListener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                        if (method.getName().equals("onConnectStatusChange")) {
                            synchronized (connection) {
                                connected = args != null && args.length == 1 && Boolean.TRUE.equals(args[0]);
                                connection.notifyAll();
                            }
                            return null;
                        }
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                        if (method.getName().equals("toString")) return "VoyahTune seat CAN connection";
                        return null;
                    });
            manager = invoke(managerClass.getMethod("getInstance", Context.class, listenerClass),
                    null, context, initListener);
            if (manager == null) throw new UnsupportedOperationException("No CanBusManager instance");
        }
        synchronized (connection) {
            while (!connected) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new TimeoutException();
                connection.wait(remaining);
            }
        }
    }

    @Override public int send(String field, int value) throws Exception {
        if (!field.equals(selectedField) || selectedState == null) throw new IllegalStateException("Not prepared");
        if (SystemClock.elapsedRealtime() >= deadline) throw new TimeoutException();
        synchronized (connection) { if (!connected) throw new TimeoutException(); }
        try {
            Object result = invoke(setter, manager, selectedState, value);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Exception | LinkageError e) {
            Log.w("VoyahSeatVoice", "OEM send failed for " + field, e);
            throw e;
        } finally {
            selectedState = null;
            selectedField = null;
        }
    }

    private void loadApi() {
        ClassLoader parent = context.getClassLoader();
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(parent);
        File framework = new File("/system/framework/QGVehicle.jar");
        if (framework.isFile()) loaders.add(new PathClassLoader(framework.getPath(), parent));
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    "com.qinggan.canbus.service", PackageManager.GET_SHARED_LIBRARY_FILES);
            List<String> paths = new ArrayList<>();
            if (info.sharedLibraryFiles != null) java.util.Collections.addAll(paths, info.sharedLibraryFiles);
            paths.add(info.sourceDir);
            if (info.splitSourceDirs != null) java.util.Collections.addAll(paths, info.splitSourceDirs);
            loaders.add(new PathClassLoader(String.join(File.pathSeparator, paths), parent));
        } catch (PackageManager.NameNotFoundException ignored) { }
        for (ClassLoader loader : loaders) {
            try {
                Class<?> api = Class.forName("com.qinggan.canbus.CanBusManager", false, loader);
                ClassLoader owner = api.getClassLoader();
                Class<?> state = Class.forName("com.qinggan.canbus.VehicleState", false, owner);
                Class<?> listener = Class.forName("com.qinggan.common.OnInitListener", false, owner);
                Method method = api.getMethod("setVehicleState", state, int.class);
                api.getMethod("getInstance", Context.class, listener);
                listener.getMethod("onConnectStatusChange", boolean.class);
                if (!listener.isInterface() || method.getReturnType() != int.class) continue;
                managerClass = api; stateClass = state; listenerClass = listener; setter = method;
                return;
            } catch (ReflectiveOperationException | LinkageError e) {
                Log.d("VoyahSeatVoice", "OEM API unavailable in class loader", e);
            }
        }
        throw new UnsupportedOperationException("CanBusManager API unavailable");
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }
}
