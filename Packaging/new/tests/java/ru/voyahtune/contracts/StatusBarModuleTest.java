package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.NavigationSourceModule;
import ru.voyahtune.agent.modules.StatusBarModule;
import ru.voyahtune.agent.policy.AirQualityPublisher;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class StatusBarModuleTest {
    private static final String HOST = "ru.big.town.anative.CarPlayFullscreenActivity";

    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("StatusBarModuleTest: passed");
    }

    static void verify() throws Throwable {
        try (Harness harness = new Harness()) {
            harness.flush();
            require(!harness.bar.visible[0] && harness.bar.visible[1] && harness.hidden[0], "CarPlay visibility not reconciled");
            require("12:0".equals(harness.savedAir), "Initial displayed PM2.5 zero was not seeded");
            int before = harness.bar.calls;
            harness.invoke(Bar.class.getDeclaredMethod("addStatusBarWindow", boolean.class), harness.bar, true);
            require(!harness.bar.visible[0] && harness.bar.calls == before + 1, "OEM show was not filtered or original was replayed");
            Method hit = Invisible.class.getDeclaredMethod("isHit", String.class, int.class);
            require(harness.invoke(hit, null, HOST, 0).equals(true) && harness.invoke(hit, null, HOST, 7).equals(false), "CarPlay hit predicate widened to virtual display");
            harness.reveal[0] = harness.now + 10000;
            harness.changed.run();
            harness.flush();
            require(harness.bar.visible[0] && !harness.hidden[0], "Temporary reveal did not show status bar");
            harness.advance(10000);
            require(!harness.bar.visible[0] && harness.hidden[0], "Temporary reveal did not expire");
            harness.top[0] = "Camera";
            harness.advance(1000);
            require(!harness.bar.visible[0] && !harness.hidden[0], "Camera lost stock hidden state or retained CarPlay ownership");
            harness.top[0] = HOST;
            harness.top[1] = HOST;
            harness.changed.run();
            harness.flush();
            require(harness.hidden[0] && harness.hidden[1], "Passenger status bar not hidden");
            harness.top[0] = "GalleryPictureMainActivity";
            Bar.pictureFullScreen = false;
            harness.advance(1000);
            require(harness.bar.visible[0] && !harness.hidden[0], "Windowed picture inherited fullscreen hidden state");
            harness.hide = false;
            harness.changed.run();
            harness.flush();
            require(harness.bar.visible[1] && !harness.hidden[1], "Hide opt-out failed to restore passenger bar");
            Method air = AirView.class.getDeclaredMethod("setPm25Incar", int.class);
            AirView view = new AirView();
            harness.invoke(air, view, 27);
            harness.invoke(air, view, 27);
            require("12:27".equals(harness.savedAir) && harness.airWrites == 2 && view.calls == 2, "PM2.5 was not published/deduplicated after OEM update");
            harness.invoke(air, view, -1);
            require("12:-1".equals(harness.savedAir), "Unknown PM2.5 retained stale measurement");
            view.failure = new IllegalStateException("Original PM2.5 failure");
            try { harness.invoke(air, view, 99); throw new AssertionError("PM2.5 error swallowed"); }
            catch (IllegalStateException failure) { require(failure == view.failure && view.calls == 4 && "12:-1".equals(harness.savedAir), "Failed PM2.5 update was replayed/published"); }
            harness.hide = true;
            harness.runtime.apply("code", 2, List.of("statusbar"), Map.of());
            harness.flush();
            require(!harness.bar.visible[1], "Status-bar timer/reconcile lost after revision");
            harness.runtime.close();
            require(harness.bar.visible[1] && !harness.hidden[1] && harness.closedWatches == 1 && harness.backend.installed.isEmpty(), "Status-bar cleanup did not release visibility/subscriptions/hooks");
            before = harness.bar.calls;
            harness.changed.run();
            harness.advance(5000);
            require(harness.bar.calls == before, "Closed observer/timer changed visibility");
        }
        try (Harness harness = new Harness()) {
            harness.bar.failure = new IllegalStateException("WindowManager unavailable");
            harness.flush();
            require(!harness.hidden[0] && harness.bar.calls == 1, "Failed hide reported hidden or replayed original");
            harness.bar.failure = null;
            harness.advance(1000);
            require(harness.hidden[0] && !harness.bar.visible[0], "Transient WindowManager failure lost owned retry");
        }
    }

    static final class Harness implements AutoCloseable, StatusBarModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ManualScheduler scheduler = new ManualScheduler();
        final ArrayDeque<Runnable> main = new ArrayDeque<>();
        final Bar bar = new Bar();
        final String[] top = {HOST, ""};
        final boolean[] hidden = new boolean[2];
        final long[] reveal = new long[2];
        final List<Exception> failures = new ArrayList<>();
        final ModuleRuntime runtime;
        Runnable changed;
        long now = 1000;
        boolean hide = true;
        int closedWatches;
        int airWrites;
        String savedAir;

        Harness() {
            Bar.pictureFullScreen = false;
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case StatusBarModule.BAR: return Bar.class;
                    case StatusBarModule.INVISIBLE: return Invisible.class;
                    case "com.qinggan.systemui.statusbar.views.StatusBarView": return AirView.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", scheduler, id -> new StatusBarModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("statusbar"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("Status bar not ready", runtime.snapshot().failure);
        }

        Object invoke(Method method, Object receiver, Object... arguments) throws Throwable { return backend.invoke(method, receiver, arguments); }
        void flush() {
            for (int attempts = 0; attempts < 20; attempts++) {
                scheduler.advance(0);
                if (main.isEmpty()) return;
                while (!main.isEmpty()) main.remove().run();
            }
            throw new AssertionError("Status-bar callbacks did not settle");
        }
        void advance(long delay) { now += delay; scheduler.advance(delay); flush(); }
        @Override public String top(int display) { return top[display]; }
        @Override public boolean hideSystemBars() { return hide; }
        @Override public long revealRemaining(int display) { return Math.max(0, reveal[display] - now); }
        @Override public void reportHidden(int display, boolean value) {
            require(!value || !bar.visible[display], "Hidden report preceded OEM visibility change");
            hidden[display] = value;
        }
        @Override public Object statusBar() { return bar; }
        @Override public void postMain(Runnable action) { main.add(action); }
        @Override public void drainMain(NavigationSourceModule.MainAction action) throws Exception { action.run(); }
        @Override public void watch(ModuleScope scope, Runnable changed) throws Exception { this.changed = changed; scope.own(() -> closedWatches++); }
        @Override public AirQualityPublisher.Storage airQuality() {
            return new AirQualityPublisher.Storage() {
                @Override public long bootCount() { return 12; }
                @Override public String read() { return savedAir; }
                @Override public void write(String value) { savedAir = value; airWrites++; }
            };
        }
        @Override public void seedAirQuality() { if (savedAir == null) AirQualityPublisher.publish(airQuality(), 0); }
        @Override public void error(Exception failure) { failures.add(failure); }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static final class Bar {
        static boolean pictureFullScreen;
        final boolean[] visible = {true, true};
        int calls;
        RuntimeException failure;
        void addStatusBarWindow(boolean visible) { apply(0, visible); }
        void addStatusBarWindowSecond(boolean visible) { apply(1, visible); }
        void apply(int display, boolean value) { calls++; if (failure != null) throw failure; visible[display] = value; }
    }
    static final class Invisible {
        static boolean isHit(String activity, int display) { return activity.equals("Camera") || activity.equals("Video") || activity.endsWith("PictureMainActivity"); }
    }
    static final class AirView {
        int calls;
        RuntimeException failure;
        void setPm25Incar(int value) { calls++; if (failure != null) throw failure; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
