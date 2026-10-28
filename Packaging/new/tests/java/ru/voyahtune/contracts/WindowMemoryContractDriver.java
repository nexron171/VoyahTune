package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.WindowMemoryProtectionModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class WindowMemoryContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        try (Harness harness = new Harness()) {
            ProcessRecord process = new ProcessRecord(input.get("process").getAsString(), input.get("adjustment").getAsInt(), input.get("foreground").getAsBoolean());
            harness.backend.invoke(OomAdjuster.class.getDeclaredMethod("applyOomAdjLocked", ProcessRecord.class, boolean.class, long.class, long.class), new OomAdjuster(), process, true, 1L, 1L);
            ActivityRecord activity = new ActivityRecord(process.windows, "com.shilapi.xcertplay." + input.get("activity").getAsString());
            boolean destroyable = (Boolean) harness.backend.invoke(ActivityRecord.class.getDeclaredMethod("isDestroyable"), activity);
            JsonObject result = new JsonObject();
            result.addProperty("adjustment", process.curAdj);
            result.addProperty("destroyable", destroyable);
            return result;
        }
    }

    static final class Harness implements AutoCloseable {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ModuleRuntime runtime;
        int recomputations;
        Harness() {
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.android.server.am.ProcessRecord": return ProcessRecord.class;
                    case "com.android.server.am.ServiceRecord": return ServiceRecord.class;
                    case "com.android.server.am.OomAdjuster": return OomAdjuster.class;
                    case "com.android.server.wm.ActivityRecord": return ActivityRecord.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new WindowMemoryProtectionModule(methods, hooks, new WindowMemoryProtectionModule.Platform() {
                @Override public void recompute() { recomputations++; }
                @Override public void error(Exception failure) {}
            }));
            runtime.apply("code", 1, List.of("windows"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("Memory module not ready", runtime.snapshot().failure);
        }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }
    static final class ProcessRecord {
        String processName;
        int curAdj;
        final Object windows = new Object();
        final ServiceRecord service;
        ProcessRecord(String name, int adjustment, boolean foreground) { processName = name; curAdj = adjustment; service = new ServiceRecord(foreground); }
        int numberOfRunningServices() { return 1; }
        ServiceRecord getRunningServiceAt(int index) { return service; }
        Object getWindowProcessController() { return windows; }
    }
    static final class ServiceRecord {
        final Component name = new Component("com.shilapi.xcertplay.DiPlaySessionService");
        boolean isForeground;
        ServiceRecord(boolean foreground) { isForeground = foreground; }
    }
    static final class Component {
        final String name;
        Component(String name) { this.name = name; }
        String getPackageName() { return "ru.big.town.restoremode"; }
        String getClassName() { return name; }
    }
    static final class ActivityRecord {
        Object app;
        Component mActivityComponent;
        boolean finishing;
        ActivityRecord(Object app, String name) { this.app = app; mActivityComponent = new Component(name); }
        boolean isDestroyable() { return true; }
    }
    static final class OomAdjuster {
        int calls;
        RuntimeException failure;
        boolean applyOomAdjLocked(ProcessRecord process, boolean all, long now, long elapsed) {
            calls++;
            if (failure != null) throw failure;
            return true;
        }
    }
}
