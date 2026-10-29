package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.WindowGeometryModule;
import ru.voyahtune.agent.modules.WindowLayout;
import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class WindowGeometryContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        try (Harness harness = new Harness()) {
            harness.scheduler.advance(1000);
            harness.settings.put("voyahtune_fullscreen_apps", "org.app");
            harness.reload();
            int width = DriveResetContractDriver.integer(input, "originalWidth", 1780);
            if (input.has("originalRequest")) width = input.getAsJsonArray("originalRequest").get(0).getAsInt();
            WindowLayoutTest.Window window = new WindowLayoutTest.Window(width, input.has("failRestoreField") ? 500 : 675);
            WindowLayoutTest.Window second = null;
            int reads = harness.reads;
            JsonArray events = input.getAsJsonArray("events");
            for (JsonElement event : events) {
                switch (event.getAsString()) {
                    case "layout": harness.layout(window); break;
                    case "fullscreen-on": harness.layout(window); break;
                    case "layout-two-same-hash":
                        window = new WindowLayoutTest.Window(input.getAsJsonArray("originalWidths").get(0).getAsInt(), 675);
                        second = new WindowLayoutTest.Window(input.getAsJsonArray("originalWidths").get(1).getAsInt(), 675);
                        harness.layout(window);
                        harness.layout(second);
                        break;
                    case "request-width-1200": window.mRequestedWidth = 1200; break;
                    case "disable":
                        harness.wmAvailable = !input.has("wmLockAvailable") || input.get("wmLockAvailable").getAsBoolean();
                        harness.failRestore = input.has("failRestoreField");
                        harness.settings.put("voyahtune_freeform", "0");
                        harness.reload();
                        break;
                    case "config-valid":
                        JsonObject initial = input.getAsJsonObject("initial");
                        harness.settings.put("voyahtune_dpi_packages", "app.maps");
                        harness.settings.put("voyahtune_dpi_app.maps", initial.get("dpi").getAsString());
                        harness.settings.put("voyahtune_fullscreen_apps", "app.maps");
                        harness.reload();
                        break;
                    case "config-invalid":
                        harness.settings.put("voyahtune_dpi_app.maps", "invalid");
                        harness.settings.put("voyahtune_win_left", "300");
                        harness.reload();
                        break;
                    case "off": harness.events.screen(false); harness.scheduler.advance(0); break;
                    case "on": harness.events.screen(true); harness.scheduler.advance(0); break;
                    case "flush": harness.scheduler.advance(10000); break;
                    default: throw new IllegalArgumentException("Unknown window state event");
                }
            }
            JsonObject result = new JsonObject();
            if (input.has("originalRequest")) {
                result.add("requestedSize", values(window.mRequestedWidth, window.mRequestedHeight));
                result.addProperty("sharedAttributeWidth", window.attrs.width);
                result.add("stableFrame", rectangle(window.displayFrames.mStable));
                result.add("contentFrame", rectangle(window.frames.mContentFrame));
                result.addProperty("originalComputes", window.computes);
                result.addProperty("hotPathSettingsReads", harness.reads - reads);
            } else if (second != null) {
                result.add("restoredWidths", values(window.mRequestedWidth, second.mRequestedWidth));
                result.addProperty("windowRecords", harness.module.trackedWindows());
            } else if (input.has("wmLockAvailable")) {
                result.addProperty("cleanupComplete", harness.module.trackedWindows() == 0);
                result.addProperty("ready", harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY);
            } else if (input.has("initial")) {
                WindowPolicy policy = harness.module.currentPolicy();
                JsonObject snapshot = new JsonObject();
                snapshot.addProperty("dpi", policy.density("app.maps"));
                snapshot.addProperty("lift", policy.liftType);
                JsonArray fullscreen = new JsonArray();
                policy.fullscreen.stream().sorted().forEach(fullscreen::add);
                snapshot.add("fullscreen", fullscreen);
                result.add("snapshot", snapshot);
                result.addProperty("partialApplied", policy.left != 145);
            } else if (input.has("originalWidth")) {
                result.addProperty("restoredWidth", window.mRequestedWidth);
                result.addProperty("remainingRecords", harness.module.trackedWindows());
            } else if (input.has("failRestoreField")) {
                result.addProperty("otherFieldsRestored", window.mRequestedHeight == 500);
                result.addProperty("ready", harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY);
                result.addProperty("cleanupComplete", harness.module.trackedWindows() == 0);
            } else {
                result.addProperty("duplicateReplacements", harness.duplicateInstalls);
                result.addProperty("duplicateTimers", harness.scheduler.activeTasks() > 0 ? 1 : 0);
            }
            harness.wmAvailable = true;
            harness.failRestore = false;
            return result;
        }
    }

    static JsonArray values(int... values) {
        JsonArray result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }
    private static JsonArray rectangle(WindowLayoutTest.Rect rect) { return values(rect.left, rect.top, rect.right, rect.bottom); }

    static final class Harness implements AutoCloseable, WindowGeometryModule.Platform {
        final ManualScheduler scheduler = new ManualScheduler();
        final FixtureHookBackend backend;
        final HookRegistry hooks;
        final Map<String, String> settings = new LinkedHashMap<>();
        final WindowGeometryModule module;
        final ModuleRuntime runtime;
        final Object wmLock = new Object();
        final Method layout = DisplayPolicy.class.getDeclaredMethod("layoutWindowLw", WindowLayoutTest.Window.class, WindowLayoutTest.Window.class, WindowLayoutTest.DisplayFrames.class);
        WindowGeometryModule.Events events;
        int reads;
        int traversals;
        int duplicateInstalls;
        int receivers;
        boolean wmAvailable = true;
        boolean failRestore;

        Harness() throws Exception { this(null, null, true); }
        Harness(FixtureHookBackend backend, HookRegistry hooks, boolean start) throws Exception {
            this.backend = backend == null ? new FixtureHookBackend(PhoneContractDriver.nativeHooks) : backend;
            this.hooks = hooks == null ? new HookRegistry(this.backend) : hooks;
            module = new WindowGeometryModule(methods(), this.hooks, this);
            this.backend.accept = target -> { if (this.backend.installed.containsKey(target)) duplicateInstalls++; return true; };
            runtime = new ModuleRuntime("code", scheduler, id -> module);
            if (!start) return;
            runtime.apply("code", 1, List.of("windows"), Map.of());
            if (runtime.snapshot().phase == ModuleRuntime.Phase.FAILED) throw new AssertionError("Geometry module failed", runtime.snapshot().failure);
        }
        MethodAccess methods() {
            return new MethodAccess(name -> {
                switch (name) {
                    case "com.android.server.wm.DisplayPolicy": return DisplayPolicy.class;
                    case "com.android.server.wm.WindowState": return WindowLayoutTest.Window.class;
                    case "com.android.server.wm.DisplayFrames": return WindowLayoutTest.DisplayFrames.class;
                    case "com.android.server.wm.ActivityRecord": return ActivityRecord.class;
                    case "com.android.server.wm.DisplayContent": return DisplayContent.class;
                    case "com.android.server.wm.WindowManagerService": return WindowManager.class;
                    case "com.android.server.wm.Task": return Task.class;
                    case "android.content.res.Configuration": return Configuration.class;
                    default: return Class.forName(name);
                }
            });
        }
        @Override public WindowPolicy policy() throws Exception { reads++; return WindowPolicy.read(settings::get, 2); }
        @Override public boolean interactive() { return true; }
        @Override public long now() { return scheduler.now(); }
        @Override public int lift() { return 2; }
        @Override public void error(Exception failure) {}
        @Override public WindowLayout.Access access() {
            return new WindowLayout.Reflection() {
                @Override public void write(Object owner, String field, Object value) throws Exception {
                    if (failRestore && field.equals("mRequestedWidth") && Integer.valueOf(1780).equals(value)) throw new IllegalStateException("Width restoration failed");
                    super.write(owner, field, value);
                }
            };
        }
        @Override public void watch(ResourceGroup resources, WindowGeometryModule.Events events) throws Exception {
            this.events = events;
            receivers = 3;
            resources.own(() -> receivers = 0);
        }
        @Override public void withWindowManager(AutoCloseable action) throws Exception {
            if (!wmAvailable) throw new IllegalStateException("WindowManager lock unavailable");
            synchronized (wmLock) { action.close(); }
        }
        @Override public void traversal() { traversals++; }
        void reload() { events.reload(); scheduler.advance(150); }
        void layout(WindowLayoutTest.Window window) throws Throwable { backend.invoke(layout, new DisplayPolicy(), window, null, window.displayFrames); }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static final class DisplayPolicy { void layoutWindowLw(WindowLayoutTest.Window window, WindowLayoutTest.Window attached, WindowLayoutTest.DisplayFrames frames) {} }
    static final class DisplayContent {
        int id;
        int getDisplayId() { return id; }
        Object getDisplayInfo() { return new WindowPrivilegesContractDriver.DisplayInfo(new JsonObject()); }
        boolean isTrusted() { return true; }
    }
    static final class WindowManager { final Object mGlobalLock = new Object(); void requestTraversal() {} }
    static final class ActivityRecord {
        Task task = new Task();
        String packageName = "org.app";
        int display;
        int originals;
        boolean ensureActivityConfiguration(int changes, boolean preserve, boolean ignoreVisibility) { originals++; return true; }
        void onDisplayChanged(DisplayContent destination) { originals++; display = destination.id; }
        int getDisplayId() { return display; }
        boolean canBeLaunchedOnDisplay(int display) { return false; }
    }
    static final class Task {
        Configuration configuration = new Configuration();
        Configuration getRequestedOverrideConfiguration() { return configuration; }
        void onRequestedOverrideConfigurationChanged(Configuration next) { configuration = next; }
    }
    static final class Configuration {
        int densityDpi;
        int bounds = 123;
        Configuration() {}
        Configuration(Configuration source) { densityDpi = source.densityDpi; bounds = source.bounds; }
    }
}
