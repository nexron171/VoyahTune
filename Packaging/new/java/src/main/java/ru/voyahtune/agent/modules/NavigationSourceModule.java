package ru.voyahtune.agent.modules;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.NavigationSourcePolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class NavigationSourceModule implements ModuleRuntime.Module {
    @FunctionalInterface public interface MainAction { void run() throws Exception; }
    public interface Platform {
        long now();
        boolean enabled() throws Exception;
        void postMain(Runnable action) throws Exception;
        void drainMain(MainAction action) throws Exception;
        void publish(Sample sample, long sampled) throws Exception;
        void error(Exception failure);
    }

    public static final class Sample {
        public final int icon;
        public final int distance;
        public final String road;
        public Sample(int icon, int distance, String road) {
            this.icon = icon;
            this.distance = distance;
            this.road = road == null ? "" : road;
        }
    }

    private static final String GUIDANCE = "com.yandex.navikit.guidance.internal.GuidanceBinding";
    private static final String DISPATCHER = "ru.dublgis.qsdk.IntegrationApplicationStateDispatcher";
    private static final Set<String> ROUTE_STATUSES = Set.of("ON_ROUTE", "RETURNED_TO_ROUTE", "WAY_POINT_REACHED");
    private final String id;
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final List<Executable> targets = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final AtomicReference<Object> candidate = new AtomicReference<>();
    private final AtomicBoolean mainPending = new AtomicBoolean();
    private final Object deliveryLock = new Object();
    private ModuleScope scope;
    private Class<?> listenerType;
    private volatile Object guidance;
    private long changedAt = -1;
    private boolean finished;
    private long stateAt = -1;
    private boolean discovered;
    private String previousMode = "";
    private volatile boolean enabled = true;
    private Sample pending;
    private long pendingTime;
    private boolean deliveryScheduled;

    public NavigationSourceModule(String id, MethodAccess methods, HookRegistry hooks, Platform platform) {
        if (!id.equals("yandex-navigation") && !id.equals("dgis-navigation")) throw new IllegalArgumentException("Unknown navigation source");
        this.id = id;
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        targets.clear();
        if (id.equals("yandex-navigation")) {
            for (String name : List.of("getNextManeuver", "route", "navigation", "onStart")) targets.add(methods.require(GUIDANCE, name));
            Constructor<?> constructor = methods.type(GUIDANCE).getDeclaredConstructor(methods.type("com.yandex.runtime.NativeObject"));
            targets.add(constructor);
            methods.type("com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre");
            methods.require(GUIDANCE, "isValid");
            methods.require(GUIDANCE, "addGuidanceListener", "java.lang.ref.WeakReference");
            methods.require(GUIDANCE, "removeGuidanceListener", "java.lang.ref.WeakReference");
            listenerType = methods.type("com.yandex.navikit.guidance.GuidanceListener");
            if (!listenerType.isInterface()) throw new IllegalArgumentException("GuidanceListener interface required");
            for (Method method : listenerType.getMethods()) {
                if (method.getDeclaringClass() != Object.class && method.getReturnType() != void.class) throw new IllegalArgumentException("Unsupported GuidanceListener return type");
            }
        } else {
            targets.add(methods.require(DISPATCHER, "setNavigationState", "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String",
                    "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String", "int"));
            targets.add(methods.require(DISPATCHER, "setActiveNavigationMode", "java.lang.String"));
            methods.require(DISPATCHER, "getActiveNavigationMode");
            methods.require(DISPATCHER, "getNavigationState");
            methods.require(DISPATCHER, "getSpeedInfo");
            OemAccess.field(methods.type(DISPATCHER), "sInstance");
        }
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        this.scope = scope;
        for (Executable target : targets) {
            scope.own(hooks.install(id, target, scope.callback(invocation -> {
                Object result = invocation.proceed();
                try {
                    if (id.equals("yandex-navigation")) capture(invocation.receiver());
                    else {
                        String mode = target.getName().equals("setActiveNavigationMode") ? String.valueOf(invocation.arguments()[0]) : null;
                        onMain(() -> {
                            if (mode == null) stateAt = platform.now();
                            else {
                                discovered = true;
                                if (!previousMode.equals(mode)) { stateAt = -1; previousMode = mode; queue(null); }
                            }
                        });
                    }
                } catch (Exception unavailable) { error(unavailable); }
                return result;
            })));
        }
        scope.own(() -> {
            platform.drainMain(this::closeBindings);
            synchronized (deliveryLock) { pending = null; deliveryScheduled = false; }
            try { platform.publish(null, platform.now()); }
            catch (Exception unavailable) { error(unavailable); }
        });
    }

    private void capture(Object next) throws Exception {
        if (guidance == next || !candidate.compareAndSet(null, next)) return;
        try {
            onMain(() -> {
                try {
                    if (!valid(next) || guidance != null && OemAccess.call(next, "route") == null) return;
                    Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "equals": return proxy == arguments[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "toString": return "VoyahTuneGuidanceListener";
                            default: onMain(() -> event(next, method.getName())); return null;
                        }
                    });
                    Binding binding = new Binding(next, listener);
                    OemAccess.call(next, "addGuidanceListener", binding.reference);
                    bindings.add(binding);
                    Object previous = guidance;
                    guidance = next;
                    changedAt = platform.now();
                    finished = false;
                    for (Binding old : new ArrayList<>(bindings)) {
                        if (old.owner == previous) {
                            try { old.close(); bindings.remove(old); }
                            catch (Exception unavailable) { error(unavailable); }
                        }
                    }
                } finally { candidate.compareAndSet(next, null); }
            });
        } catch (Exception failure) { candidate.compareAndSet(next, null); throw failure; }
    }

    private void event(Object owner, String name) throws Exception {
        if (owner != guidance) return;
        switch (name) {
            case "onBackgroundGuidanceWillBeSuspended": changedAt = -1; queue(null); break;
            case "onLocationUpdated":
            case "onRoutePositionUpdated": changedAt = platform.now(); break;
            case "onRouteChanged": finished = false; changedAt = platform.now(); queue(null); break;
            case "onFinishedRoute": finished = true; queue(null); break;
            default: break;
        }
    }

    private void onMain(MainAction action) throws Exception {
        platform.postMain(scope.listener(() -> {
            try { action.run(); }
            catch (Exception unavailable) { error(unavailable); }
        }));
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        synchronized (deliveryLock) { pending = null; deliveryScheduled = false; }
        scope.schedule(0, this::tick);
    }

    private void tick() {
        try {
            enabled = platform.enabled();
            if (!enabled) queue(null);
            else if (mainPending.compareAndSet(false, true)) {
                try {
                    onMain(() -> {
                        try { queue(id.equals("yandex-navigation") ? yandex() : dgis()); }
                        catch (Exception unavailable) { queue(null); error(unavailable); }
                        finally { mainPending.set(false); }
                    });
                } catch (Exception failure) { mainPending.set(false); throw failure; }
            }
        } catch (Exception unavailable) { error(unavailable); }
        try { scope.schedule(1000, this::tick); }
        catch (Exception unavailable) { error(unavailable); }
    }

    private Sample yandex() throws Exception {
        Object current = guidance;
        if (current == null || !valid(current) || finished || changedAt < 0 || platform.now() - changedAt >= 30000 || OemAccess.call(current, "route") == null) return null;
        Object next = OemAccess.call(current, "getNextManeuver");
        if (next == null) return null;
        Object engine = OemAccess.call(OemAccess.call(current, "navigation"), "getGuidance");
        String status = String.valueOf(OemAccess.call(OemAccess.call(engine, "getRouteStatus"), "name"));
        if (OemAccess.call(engine, "getCurrentRoute") == null || !ROUTE_STATUSES.contains(status)) return null;
        Object point = OemAccess.call(next, "getPoint");
        List<?> maneuvers = (List<?>) OemAccess.call(OemAccess.call(engine, "getWindshield"), "getManoeuvres");
        for (Object maneuver : maneuvers) {
            Object position = OemAccess.call(OemAccess.call(maneuver, "getPosition"), "getPoint");
            if (!samePoint(point, position)) continue;
            double distance = ((Number) OemAccess.call(next, "getDistance")).doubleValue();
            if (!Double.isFinite(distance) || distance < 0 || distance > Integer.MAX_VALUE) return null;
            Object annotation = OemAccess.call(maneuver, "getAnnotation");
            String action = String.valueOf(OemAccess.call(OemAccess.call(annotation, "getAction"), "name"));
            Object road = OemAccess.call(annotation, "getToponym");
            return new Sample(NavigationSourcePolicy.yandexIcon(action), (int) Math.round(distance), road == null ? "" : road.toString());
        }
        return null;
    }

    private boolean samePoint(Object first, Object second) throws Exception {
        for (String coordinate : List.of("getLatitude", "getLongitude")) {
            double left = ((Number) OemAccess.call(first, coordinate)).doubleValue();
            double right = ((Number) OemAccess.call(second, coordinate)).doubleValue();
            if (!Double.isFinite(left) || !Double.isFinite(right) || Math.abs(left - right) > 0.00001) return false;
        }
        return true;
    }

    private Sample dgis() throws Exception {
        Object dispatcher = OemAccess.read(methods.type(DISPATCHER), "sInstance");
        if (dispatcher == null) return null;
        String mode = String.valueOf(OemAccess.call(dispatcher, "getActiveNavigationMode"));
        if (!discovered) { discovered = true; previousMode = mode; stateAt = platform.now(); }
        if (!NavigationSourcePolicy.driving(mode) || stateAt < 0 || platform.now() - stateAt >= 30000
                || Boolean.TRUE.equals(OemAccess.read(OemAccess.call(dispatcher, "getSpeedInfo"), "mBadLocation"))) return null;
        Object state = OemAccess.call(dispatcher, "getNavigationState");
        int distance = NavigationSourcePolicy.meters(String.valueOf(OemAccess.read(state, "mManeuverDistance")));
        String icon = String.valueOf(OemAccess.read(state, "mManeuverIcon"));
        if (distance < 0 || icon.isEmpty()) return null;
        Object road = OemAccess.read(state, "mManeuverDescription");
        return new Sample(NavigationSourcePolicy.dgisIcon(icon), distance, road == null ? "" : road.toString());
    }

    private boolean valid(Object value) throws Exception { return Boolean.TRUE.equals(OemAccess.call(value, "isValid")); }

    private void queue(Sample sample) throws Exception {
        synchronized (deliveryLock) {
            pending = sample;
            pendingTime = platform.now();
            if (deliveryScheduled) return;
            deliveryScheduled = true;
        }
        try { scope.schedule(0, this::deliver); }
        catch (Exception failure) { synchronized (deliveryLock) { deliveryScheduled = false; } throw failure; }
    }

    private void deliver() {
        Sample sample;
        long sampled;
        synchronized (deliveryLock) {
            sample = pending;
            sampled = pendingTime;
            pending = null;
            deliveryScheduled = false;
        }
        try { platform.publish(enabled ? sample : null, sampled); }
        catch (Exception unavailable) { error(unavailable); }
    }

    private void closeBindings() throws Exception {
        Exception failure = null;
        for (Binding binding : new ArrayList<>(bindings)) {
            try { binding.close(); bindings.remove(binding); }
            catch (Exception unavailable) { if (failure == null) failure = unavailable; else failure.addSuppressed(unavailable); }
        }
        guidance = null;
        candidate.set(null);
        if (failure != null) throw failure;
    }

    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Diagnostics cannot invalidate OEM return values. */ }
    }

    private final class Binding {
        final Object owner;
        final Object listener;
        final WeakReference<Object> reference;
        Binding(Object owner, Object listener) { this.owner = owner; this.listener = listener; reference = new WeakReference<>(listener); }
        void close() throws Exception { if (valid(owner)) OemAccess.call(owner, "removeGuidanceListener", reference); }
    }
}
