package ru.voyahtune.contracts;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.NavigationSourceModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class NavigationSourceModuleTest {
    private static final ThreadLocal<Harness> active = new ThreadLocal<>();

    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("NavigationSourceModuleTest: passed");
    }

    static void verify() throws Throwable {
        try (Harness harness = new Harness("yandex-navigation")) {
            Guidance route = new Guidance();
            harness.flush();
            require(harness.last() == null, "Absent Yandex route was published active");
            harness.invoke(Guidance.class.getDeclaredMethod("getNextManeuver"), route);
            harness.flush();
            harness.advance(1000);
            require(harness.last().icon == 7 && harness.last().distance == 250 && harness.last().road.equals("Yandex road"), "Late attached Yandex route was not captured");
            require(route.additions == 1, "Polling duplicated guidance listeners");
            route.routeStatus = Status.NOT_ON_ROUTE;
            harness.advance(1000);
            require(harness.last() == null, "Off-route Yandex maneuver remained active");
            route.routeStatus = Status.ON_ROUTE;
            route.listener.get().onRoutePositionUpdated();
            harness.advance(1000);
            require(harness.last() != null, "Fresh route position did not revive guidance");
            route.listener.get().onFinishedRoute();
            harness.flush();
            require(harness.last() == null, "Finished route was not cleared immediately");
            harness.advance(1000);
            require(harness.last() == null, "Polling revived finished route");
            route.listener.get().onRouteChanged();
            harness.advance(1000);
            require(harness.last() != null, "New route was not accepted");
            harness.advance(30000);
            require(harness.last() == null, "Polling refreshed stale Yandex route");
            route.listener.get().onLocationUpdated();
            harness.advance(1000);
            require(harness.last() != null, "Location update did not refresh Yandex route");
            route.maneuverPoint.latitude = 56;
            harness.advance(1000);
            require(harness.last() == null, "Distance matched another maneuver annotation");
            route.maneuverPoint.latitude = 55;
            route.distance = Double.POSITIVE_INFINITY;
            harness.advance(1000);
            require(harness.last() == null, "Invalid Yandex distance was published");
            route.distance = 250;
            route.listener.get().onJamForecastUpdated();
            harness.advance(30000);
            require(harness.last() == null, "Unrelated listener event refreshed stale route");
            Guidance next = harness.construct();
            harness.flush();
            harness.advance(1000);
            require(next.additions == 1 && route.removals == 1 && harness.last() != null, "Constructor capture did not replace previous guidance");
            harness.enabled = false;
            harness.advance(1000);
            require(harness.last() == null, "Disabled navigation remained active");
            harness.runtime.apply("code", 2, List.of("yandex-navigation"), Map.of());
            harness.enabled = true;
            harness.flush();
            require(harness.last() != null, "Polling lost after configuration revision");
            harness.runtime.close();
            require(next.removals == 1 && harness.backend.installed.isEmpty() && harness.backend.constructors.isEmpty(), "Navigation cleanup left listener/hooks");
            int messages = harness.samples.size();
            harness.advance(5000);
            next.listener.get().onRoutePositionUpdated();
            harness.flush();
            require(harness.samples.size() == messages, "Closed navigation retained timers/listener effects");
        }
        try (Harness harness = new Harness("dgis-navigation")) {
            harness.flush();
            require(harness.last().icon == 7 && harness.last().distance == 1200, "2GIS existing singleton was not discovered");
            harness.state("50 м", "crossroad_right");
            harness.advance(1000);
            require(harness.last().icon == 3 && harness.last().distance == 50, "2GIS state was not refreshed");
            harness.mode("freeroam");
            harness.flush();
            require(harness.last() == null, "2GIS non-car mode not cleared immediately");
            harness.mode("car");
            harness.advance(1000);
            require(harness.last() == null, "2GIS mode change revived previous maneuver");
            harness.state("90 m", "crossroad_left");
            harness.advance(1000);
            require(harness.last().distance == 90, "2GIS fresh state missing");
            Dispatcher.sInstance.speed.mBadLocation = true;
            harness.advance(1000);
            require(harness.last() == null, "2GIS bad location remained active");
            Dispatcher.sInstance.speed.mBadLocation = false;
            harness.advance(30000);
            require(harness.last() == null, "2GIS stale state was refreshed by polling");
            harness.state("bad distance", "crossroad_left");
            harness.advance(1000);
            require(harness.last() == null, "Invalid 2GIS distance published");
        }
    }

    static final class Harness implements AutoCloseable, NavigationSourceModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ManualScheduler scheduler = new ManualScheduler();
        final ArrayDeque<Runnable> main = new ArrayDeque<>();
        final List<NavigationSourceModule.Sample> samples = new ArrayList<>();
        final ModuleRuntime runtime;
        final Constructor<Guidance> constructor = Guidance.class.getDeclaredConstructor(NativeObject.class);
        long now = 1000;
        boolean enabled = true;
        boolean onMain;

        Harness(String module) throws Exception {
            active.set(this);
            Dispatcher.sInstance = new Dispatcher();
            backend.constructorBackups.put(constructor, Guidance.class.getDeclaredMethod("initialize", NativeObject.class));
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.yandex.navikit.guidance.internal.GuidanceBinding": return Guidance.class;
                    case "com.yandex.runtime.NativeObject": return NativeObject.class;
                    case "com.yandex.navikit.guidance.GuidanceListener": return Listener.class;
                    case "com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre": return Upcoming.class;
                    case "ru.dublgis.qsdk.IntegrationApplicationStateDispatcher": return Dispatcher.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", scheduler, id -> new NavigationSourceModule(id, methods, hooks, this));
            runtime.apply("code", 1, List.of(module), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("Navigation not ready", runtime.snapshot().failure);
        }

        Object invoke(Method target, Object receiver, Object... arguments) throws Throwable { return backend.invoke(target, receiver, arguments); }
        Guidance construct() throws Throwable {
            if (PhoneContractDriver.nativeHooks) return new Guidance(new NativeObject());
            Guidance guidance = new Guidance();
            backend.constructors.get(constructor).onCall(new Object[] {guidance, new NativeObject()});
            return guidance;
        }
        void mode(String mode) throws Throwable { invoke(Dispatcher.class.getDeclaredMethod("setActiveNavigationMode", String.class), Dispatcher.sInstance, mode); }
        void state(String distance, String icon) throws Throwable {
            invoke(Dispatcher.class.getDeclaredMethod("setNavigationState", String.class, String.class, String.class, String.class,
                    String.class, String.class, String.class, String.class, int.class), Dispatcher.sInstance, "", "", "", distance, icon, "New road", "", "", 20);
        }
        void flush() {
            for (int attempts = 0; attempts < 20; attempts++) {
                scheduler.advance(0);
                if (main.isEmpty()) return;
                while (!main.isEmpty()) {
                    onMain = true;
                    try { main.remove().run(); }
                    finally { onMain = false; }
                }
            }
            throw new AssertionError("Navigation callbacks did not settle");
        }
        void advance(long millis) { now += millis; scheduler.advance(millis); flush(); }
        NavigationSourceModule.Sample last() { return samples.get(samples.size() - 1); }
        @Override public long now() { return now; }
        @Override public boolean enabled() { return enabled; }
        @Override public void postMain(Runnable action) { main.add(action); }
        @Override public void drainMain(NavigationSourceModule.MainAction action) throws Exception {
            onMain = true;
            try { action.run(); } finally { onMain = false; }
        }
        @Override public void publish(NavigationSourceModule.Sample sample, long sampled) {
            require(!onMain && sampled == now, "Provider IPC ran on navigation main thread or sampled time changed");
            samples.add(sample);
        }
        @Override public void error(Exception failure) { throw new AssertionError("Navigation fixture error", failure); }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); active.remove(); }
    }

    public interface Listener {
        void onLocationUpdated(); void onRoutePositionUpdated(); void onRouteChanged(); void onFinishedRoute();
        void onBackgroundGuidanceWillBeSuspended(String reason); void onJamForecastUpdated();
    }
    static final class NativeObject {}
    static final class Guidance {
        boolean valid = true;
        boolean routeActive = true;
        Status routeStatus = Status.ON_ROUTE;
        double distance = 250;
        final Point nextPoint = new Point();
        final Point maneuverPoint = new Point();
        WeakReference<Listener> listener;
        int additions;
        int removals;
        Guidance() {}
        Guidance(NativeObject value) { initialize(value); }
        void initialize(NativeObject value) { valid = true; }
        boolean isValid() { return valid; }
        Object route() { return routeActive ? this : null; }
        Guidance navigation() { return this; }
        void onStart() {}
        Next getNextManeuver() { return new Next(this); }
        Guidance getGuidance() { return this; }
        Status getRouteStatus() { return routeStatus; }
        Object getCurrentRoute() { return routeActive ? this : null; }
        Guidance getWindshield() { return this; }
        List<Upcoming> getManoeuvres() { return List.of(new Upcoming(this)); }
        void addGuidanceListener(WeakReference<Listener> value) { require(active.get().onMain, "Listener attached off main"); listener = value; additions++; }
        void removeGuidanceListener(WeakReference<Listener> value) { require(active.get().onMain && value == listener, "Wrong listener removal"); removals++; }
    }
    enum Status { ON_ROUTE, NOT_ON_ROUTE }
    static final class Point { double latitude = 55; double getLatitude() { return latitude; } double getLongitude() { return 37; } }
    static final class Next { final Guidance owner; Next(Guidance owner) { this.owner = owner; } Point getPoint() { return owner.nextPoint; } double getDistance() { return owner.distance; } }
    static final class Upcoming { final Guidance owner; Upcoming(Guidance owner) { this.owner = owner; } Upcoming getPosition() { return this; } Point getPoint() { return owner.maneuverPoint; } Annotation getAnnotation() { return new Annotation(); } }
    static final class Annotation { Action getAction() { return Action.LEFT; } String getToponym() { return "Yandex road"; } }
    enum Action { LEFT }
    static final class Dispatcher {
        static Dispatcher sInstance;
        String mode = "car";
        final NavigationState state = new NavigationState();
        final Speed speed = new Speed();
        void setNavigationState(String arrival, String remaining, String total, String distance, String icon, String road, String barrier, String barrierDescription, int progress) {
            state.mManeuverDistance = distance; state.mManeuverIcon = icon; state.mManeuverDescription = road;
        }
        void setActiveNavigationMode(String mode) { this.mode = mode; }
        String getActiveNavigationMode() { return mode; }
        NavigationState getNavigationState() { return state; }
        Speed getSpeedInfo() { return speed; }
    }
    static final class NavigationState { String mManeuverDistance = "1,2 км"; String mManeuverIcon = "crossroad_left"; String mManeuverDescription = "Road"; }
    static final class Speed { boolean mBadLocation; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
