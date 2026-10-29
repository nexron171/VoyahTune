package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.LauncherDockModule;
import ru.voyahtune.agent.modules.LauncherPlatform;
import ru.voyahtune.agent.policy.LauncherCommand;
import ru.voyahtune.agent.policy.LauncherPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class LauncherDockContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        try (Harness harness = new Harness(input.get("adapter").getAsString().equals("shared-navigation"))) {
            String activity = input.get("top").getAsString();
            harness.tops[0] = activity.equals("Home") ? new LauncherPolicy.Top(LauncherPolicy.LAUNCHER, "MainActivity", true)
                    : new LauncherPolicy.Top(LauncherPolicy.NATIVE, activity, true);
            harness.settings.put("fullscreen_apps", input.get("fullscreen").getAsBoolean() ? LauncherPolicy.NATIVE : "");
            harness.settings.put("carplay_hide_system_bars", input.get("hideSystemBars").getAsBoolean() ? "1" : "0");
            harness.flush(350);
            harness.events.reload();
            harness.events.reload();
            harness.flush(350);
            JsonObject result = new JsonObject();
            result.addProperty("dockVisible", harness.driver.mRootView.parent != null);
            int duplicates = 0;
            for (View view : harness.driver.views()) duplicates += Math.max(0, view.listeners.size() - 1);
            result.addProperty("duplicateListeners", duplicates);
            return result;
        }
    }

    static class Harness implements AutoCloseable, LauncherPlatform {
        final boolean shared;
        final ManualScheduler scheduler = new ManualScheduler();
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final List<Runnable> main = new ArrayList<>();
        final Map<String, String> settings = new HashMap<>();
        final List<LauncherCommand> commands = new ArrayList<>();
        final LauncherPolicy.Top[] tops = {new LauncherPolicy.Top(LauncherPolicy.LAUNCHER, "MainActivity", true), new LauncherPolicy.Top(LauncherPolicy.LAUNCHER, "MainActivity", true)};
        final boolean[] hidden = new boolean[2];
        final boolean[] reported = new boolean[2];
        final boolean[] statusVisible = {true, true};
        final long[] revealUntil = new long[2];
        final Bar driver = new Bar(0);
        final Bar passenger = new Bar(1);
        final Model model;
        final ModuleRuntime runtime;
        final LauncherDockModule module;
        Events events;
        int closedWatchers;

        Harness(boolean shared) { this(shared, true); }
        Harness(boolean shared, boolean start) {
            this.shared = shared;
            model = new Model(this, shared ? driver : new Controller(driver), shared ? passenger : new Controller(passenger));
            settings.put("freeform", "1");
            settings.put("dockpin", "1");
            MethodAccess methods = new MethodAccess(name -> target(name, shared));
            module = new LauncherDockModule(methods, hooks, this);
            runtime = new ModuleRuntime("code", scheduler, id -> module);
            if (!start) return;
            runtime.apply("code", 1, List.of("launcher"), Map.of());
            if (runtime.snapshot().phase == ModuleRuntime.Phase.FAILED) throw new AssertionError("Launcher dock failed", runtime.snapshot().failure);
        }
        static Class<?> target(String name, boolean shared) throws ClassNotFoundException {
                switch (name) {
                    case "com.qinggan.launcher.navigation.NavigationBarMain": if (shared) throw new ClassNotFoundException(name); return Bar.class;
                    case "com.qinggan.mainlauncher.navigation.NavigationBar": if (!shared) throw new ClassNotFoundException(name); return Bar.class;
                    case "com.qinggan.launcher.navigation.NavigationBarController": return Controller.class;
                    case "com.qinggan.app.launcher.LauncherModel": return Model.class;
                    case "com.qinggan.secondlauncher.activity.SecondMainActivity": return SecondActivity.class;
                    case "com.qinggan.launcher.base.drag.ThirdAppUtil": return ThirdApp.class;
                    case "android.view.View": return View.class;
                    case "android.content.Context": return Context.class;
                    case "android.content.Intent": return Intent.class;
                    default: return Class.forName(name);
                }
        }
        @Override public Map<String, String> settings() { return new HashMap<>(settings); }
        @Override public String setting(String key) { return settings.getOrDefault(key, "none"); }
        @Override public LauncherPolicy.Top top(int display, boolean shared) { return tops[display]; }
        @Override public long now() { return scheduler.now(); }
        @Override public int lift() { return 2; }
        @Override public long revealRemaining(int display) {
            long remaining = revealUntil[display] - now();
            return remaining > 0 && remaining <= 10000 ? remaining : 0;
        }
        @Override public boolean chromeHidden(int display) { return hidden[display]; }
        @Override public void chromeHidden(int display, boolean value) { hidden[display] = value; }
        @Override public boolean syncStatusBar(int display, boolean visible) { statusVisible[display] = visible; return true; }
        @Override public void reportDockHidden(int display, boolean value) { reported[display] = value; }
        @Override public Object model() { return model; }
        @Override public List<?> roots() { return List.of(); }
        @Override public List<String> installed() { return List.of(); }
        @Override public boolean available(String target) { return !target.equals("none"); }
        @Override public Object appIcon(String target) { return "app:" + target; }
        @Override public CharSequence label(String target) { return target; }
        @Override public void invalidatePackage(String name) {}
        @Override public boolean execute(LauncherCommand command) { commands.add(command); return true; }
        @Override public Object listener(LongGesture gesture) { return gesture; }
        @Override public void postMain(Runnable action) { main.add(action); }
        @Override public void drainMain(AutoCloseable cleanup) throws Exception { main.clear(); cleanup.close(); }
        @Override public void watch(ResourceGroup resources, Events events) throws Exception { this.events = events; resources.own(() -> closedWatchers++); }
        @Override public void error(Exception failure) {}
        @Override public Object read(Object raw, String property) { return ((View) raw).properties.get(property); }
        @Override public void write(Object raw, String property, Object value) {
            View view = (View) raw;
            view.properties.put(property, value);
            if (property.equals("longClick") && value != null && !view.listeners.contains(value)) view.listeners.add(value);
        }
        @Override public AutoCloseable retain(Object raw, Object state) { View view = (View) raw; view.anchors.add(state); return () -> view.anchors.remove(state); }
        @Override public Object icon(String target, int slot, String style) { return style.equals("music") ? "music" : style.equals("navigation") ? "navigation" : "app:" + target; }
        @Override public void invalidate(Object view) {}
        @Override public boolean layout(Object view) { return true; }
        void flush(long millis) {
            scheduler.advance(millis);
            for (int count = 0; !main.isEmpty(); count++) {
                if (count > 100) throw new AssertionError("Unbounded launcher main replay");
                main.remove(0).run();
                scheduler.advance(0);
            }
        }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static final class View {
        final int id;
        final Map<String, Object> properties = new HashMap<>(Map.of("background", "OEM", "height", 90, "visibility", 0, "longClickable", false, "checked", false));
        final List<Object> anchors = new ArrayList<>();
        final List<Object> listeners = new ArrayList<>();
        RuntimeException clickFailure;
        View(int id) { this.id = id; }
        int getId() { return id; }
        Display getDisplay() { return new Display(0); }
        void performClick() { if (clickFailure != null) throw clickFailure; }
    }
    static final class Display { final int id; Display(int id) { this.id = id; } int getDisplayId() { return id; } }
    static final class Bar {
        final int mScreenId;
        final View mScreenUpView = new View(10), mScreenDownView = new View(11), mScreenUpRadioGroup = new View(12);
        final View mScreenUpHomeView = new View(1), mScreenUpAllAppView = new View(2), mScreenUpItemView1 = new View(3), mScreenUpItemView2 = new View(4);
        final View mScreenUpItemView3 = new View(5), mScreenUpItemView4 = new View(6), mScreenUpTemperatureContentView = new View(7);
        final Root mRootView = new Root();
        final Parameters mLp = new Parameters();
        final Manager mWindowManager = new Manager();
        final Object mMoveWindowAnimator = null;
        int stockClicks;
        String selected;
        Bar(int display) { mScreenId = display; mScreenUpView.properties.put("height", 720); mScreenUpRadioGroup.properties.put("height", -1); }
        void updateTheme() {}
        void initScreenUpViews() {}
        void updateSelectedApp(String name, String activity) { selected = name; }
        void onClick(View view) { stockClicks++; }
        void doScreenLift(int type) {}
        void dismiss() { mRootView.parent = null; }
        void show() { mRootView.parent = new Object(); mLp.x = 0; }
        boolean isShowing() { return mRootView.parent != null; }
        List<View> views() { return List.of(mScreenUpHomeView, mScreenUpAllAppView, mScreenUpItemView1, mScreenUpItemView2); }
    }
    static final class Controller {
        final int mScreenId;
        final Bar mNavigationBar;
        final Root mRootView;
        final Parameters mLp;
        final Manager mWindowManager;
        final Object mMoveWindowAnimator = null;
        Controller(Bar bar) { mNavigationBar = bar; mScreenId = bar.mScreenId; mRootView = bar.mRootView; mLp = bar.mLp; mWindowManager = bar.mWindowManager; }
        void doScreenLift(int type) {}
        void show() { mNavigationBar.show(); }
        void dismiss() { mNavigationBar.dismiss(); }
        boolean isShowing() { return mNavigationBar.isShowing(); }
    }
    static final class Root { Object parent = new Object(); Object getParent() { return parent; } }
    static final class Parameters { int x; int width = 145; }
    static final class Manager { void updateViewLayout(Root root, Parameters parameters) {} void removeView(Root root) { root.parent = null; } }
    static final class Model {
        final Harness harness;
        final Object mMainScreenNavigationBar;
        final Object mSecondScreenNavigationBar;
        Model(Harness harness, Object driver, Object passenger) { this.harness = harness; mMainScreenNavigationBar = driver; mSecondScreenNavigationBar = passenger; }
        void handleUpdateMainNavigationBar(String name, String activity, boolean visible) { visibility(mMainScreenNavigationBar, visible); }
        void handleUpdateSecondNavigationBar(String name, String activity, boolean visible) { visibility(mSecondScreenNavigationBar, visible); }
        private void visibility(Object raw, boolean visible) {
            try { harness.backend.invoke(raw.getClass().getDeclaredMethod(visible ? "show" : "dismiss"), raw); }
            catch (RuntimeException failure) { throw failure; }
            catch (Throwable failure) { throw new AssertionError(failure); }
        }
        void onReceive(Context context, Intent intent) {}
        void onReceive(Context context, LauncherAllAppsModuleTest.Intent intent) {}
        void onMoveStart(String name, String activity, int type, int display, int x, int other) {}
        void onMoveStop(String name, String activity, int type, int display, int x, int other) {}
        void doScreenLift(int type) {}
        boolean isThirdShowFloatApp(String name) { return true; }
    }
    static final class Context {}
    static final class Intent { String action = ""; String getAction() { return action; } int getIntExtra(String name, int fallback) { return fallback; } }
    static final class SecondActivity { void onResume() {} Manager getWindowManager() { return new Manager(); } }
    static final class ThirdApp { static boolean isThirdShowFloatApp(String name) { return true; } }
}
