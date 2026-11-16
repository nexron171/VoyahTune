package ru.voyahtune.agent.modules;

import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.OemAccess;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

final class PassengerHeadphonesOem {
    static final String SERVICE = "com.qinggan.audiopolicy.service.OSAudioPolicyService";

    final Method packageGroup;
    final Method supportedPackage;
    final Method matchAttributes;
    final Method matchDisplay;
    final Method switchChannel;
    final Method switchDisplay;
    final Method connectionChanged;
    final Method requestFocus;
    final Method requestSourceFocus;
    final Method supportsVolume;
    final Method serviceCreated;
    final Object sourceLock;

    private final Object cabinLock;
    private final Field engineCollector;
    private final Field collectorFocus;
    private final Field collectorEngine;
    private final Field focusCollector;
    private final Field groups;
    private final Field displays;
    private final Field cabinClients;
    private final Method sourceOwner;
    private final Method detachSource;
    private final Method currentMedia;
    private final Method requestCabin;
    private final Method clientPackage;
    private final Method clientUsage;
    private final Method clientConflict;
    private final Method connected;
    private final Method notifyCabin;
    private final Class<?> bundleType;

    PassengerHeadphonesOem(MethodAccess methods) throws Exception {
        // Follow declared types: JADX's defpackage prefix is not part of the runtime names.
        Class<?> service = methods.type(SERVICE);
        Class<?> engine = service.getDeclaredField("b").getType();
        engineCollector = OemAccess.field(engine, "u");
        Class<?> collector = engineCollector.getType();
        collectorEngine = field(collector, "h", engine, false);
        collectorFocus = OemAccess.field(collector, "f");
        Class<?> focus = collectorFocus.getType();
        Class<?> client = focus.getDeclaredField("n").getType();
        focusCollector = field(focus, "d", collector, false);
        groups = field(collector, "n", String[].class, false);
        displays = field(collector, "c", ConcurrentHashMap.class, false);
        cabinClients = field(focus, "g", Stack.class, false);
        sourceLock = field(focus, "m", Object.class, true).get(null);
        cabinLock = field(focus, "e", Object.class, true).get(null);
        bundleType = methods.type("android.os.Bundle");
        Class<?>[] request = {String.class, methods.type("android.os.IBinder"),
                client.getDeclaredField("a").getType(), int.class, int.class,
                String.class, int.class, int.class, int.class};

        packageGroup = method(collector, "a", String.class, String.class);
        supportedPackage = method(collector, "d", boolean.class, String.class);
        matchAttributes = method(collector, "a", boolean.class, String.class, int.class, int.class);
        matchDisplay = method(collector, "a", boolean.class, String.class, int.class, int.class, int.class);
        switchChannel = method(collector, "b", void.class, String.class, int.class);
        switchDisplay = method(collector, "a", void.class, bundleType);
        connectionChanged = method(collector, "c", void.class, boolean.class);
        connected = method(collector, "e", boolean.class);
        requestFocus = method(focus, "b", int.class, request);
        requestSourceFocus = method(focus, "a", int.class, request);
        sourceOwner = method(focus, "a", client);
        detachSource = method(focus, "b", client);
        currentMedia = method(focus, "f", client);
        requestCabin = method(focus, "c", int.class, client);
        clientPackage = method(client, "l", String.class);
        clientUsage = method(client, "k", int.class);
        clientConflict = method(client, "a", void.class, int.class);
        notifyCabin = method(engine, "X", void.class);
        serviceCreated = method(service, "onCreate", void.class);

        String collectorName = collector.getName();
        String prefix = collectorName.substring(0, collectorName.lastIndexOf('.') + 1);
        Class<?> configuration = methods.type(prefix + "be");
        field(configuration, "e", String[].class, true);
        supportsVolume = checkedMethod(configuration, "f", boolean.class, true, String.class);
    }

    Object collector(Object engine) throws IllegalAccessException {
        return engineCollector.get(engine);
    }

    Object collectorForFocus(Object focus) throws IllegalAccessException {
        return focusCollector.get(focus);
    }

    Object focus(Object collector) throws IllegalAccessException {
        return collectorFocus.get(collector);
    }

    boolean stockPackage(Object collector, String packageName) throws IllegalAccessException {
        for (String group : (String[]) groups.get(collector)) {
            if (group.contains(packageName)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    ConcurrentHashMap<String, Integer> displays(Object collector) throws IllegalAccessException {
        return (ConcurrentHashMap<String, Integer>) displays.get(collector);
    }

    boolean hasCommunication(Object collector, String packageName) throws Exception {
        // Snapshot the Vector storage without taking the OEM cabin monitor (cabin -> source lock order).
        Object focus = focus(collector);
        if (focus == null) return false;
        Object[] clients = ((Stack<?>) cabinClients.get(focus)).toArray();
        for (Object client : clients) {
            if (packageName.equals(packageName(client)) && communication(usage(client))) return true;
        }
        return false;
    }

    static boolean communication(int usage) {
        return usage == 2 || usage == 3 || usage == 6;
    }

    String packageName(Object client) throws Exception {
        return (String) invoke(clientPackage, client);
    }

    private int usage(Object client) throws Exception {
        return (Integer) invoke(clientUsage, client);
    }

    Object source(Object collector) throws Exception {
        Object focus = focus(collector);
        return focus == null ? null : invoke(sourceOwner, focus);
    }

    Object detachOwnedSource(Object collector, Set<String> packages) throws Exception {
        synchronized (sourceLock) {
            Object source = source(collector);
            return source != null && packages.contains(packageName(source))
                    ? invoke(detachSource, focus(collector)) : null;
        }
    }

    void stop(Object client) throws Exception {
        if (client != null) invoke(clientConflict, client, 301);
    }

    void restoreFocus(Object collector, Set<String> packages) throws Exception {
        Object source = detachOwnedSource(collector, packages);
        if (source == null) return;
        try {
            int result = (Integer) invoke(requestCabin, focus(collector), source);
            if ((result & 1) == 0) stop(source);
        } catch (Exception failure) {
            try {
                stop(source);
            } catch (Exception stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            throw failure;
        } finally {
            invoke(notifyCabin, collectorEngine.get(collector));
        }
    }

    void reconcilePlayingMedia(Object collector, Set<String> packages) throws Exception {
        // Match OEM lock order and keep bootstrap from replacing a concurrently acquired source owner.
        synchronized (cabinLock) {
            synchronized (sourceLock) {
                if (!(Boolean) invoke(connected, collector) || source(collector) != null) return;
                Object client = invoke(currentMedia, focus(collector));
                if (client == null) return;
                String packageName = packageName(client);
                if (!packages.contains(packageName)
                        || !Integer.valueOf(1).equals(displays(collector).get(packageName))) return;
                Object bundle = OemAccess.construct(bundleType);
                OemAccess.call(bundle, "putCharSequence", "appinfo", packageName);
                OemAccess.call(bundle, "putShort", "disp", (short) 1);
                invoke(switchDisplay, collector, bundle);
            }
        }
    }

    void route(Object collector, String packageName, int channel) throws Exception {
        synchronized (sourceLock) {
            invoke(switchChannel, collector, packageName, channel);
        }
    }

    static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new ReflectiveOperationException(cause);
        }
    }

    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic) throws Exception {
        Field field = OemAccess.field(owner, name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic) {
            throw new NoSuchFieldException("Unsupported OEM headphone field: " + field);
        }
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        return checkedMethod(owner, name, result, false, parameters);
    }

    private static Method checkedMethod(Class<?> owner, String name, Class<?> result,
            boolean isStatic, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException("Unsupported OEM headphone method: " + method);
        }
        method.setAccessible(true);
        return method;
    }
}
