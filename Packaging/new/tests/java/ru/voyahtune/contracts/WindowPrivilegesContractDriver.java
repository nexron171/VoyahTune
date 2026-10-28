package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.WindowPrivilegesModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class WindowPrivilegesContractDriver {
    static final ThreadLocal<Harness> active = new ThreadLocal<>();

    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        try (Harness harness = new Harness()) {
            JsonObject result = new JsonObject();
            if (contract.equals("windows.ime")) {
                harness.stock = input.get("stockResult").getAsBoolean();
                DisplayContent display = new DisplayContent(input.getAsJsonObject("display"));
                result.addProperty("result", (Boolean) harness.backend.invoke(DisplaySettings.class.getDeclaredMethod("shouldShowImeLocked", DisplayContent.class), new DisplaySettings(), display));
                result.addProperty("originalCalls", harness.originals);
            } else {
                harness.nativeUid = input.get("nativeUid").getAsInt();
                harness.callerUid = input.get("callerUid").getAsInt();
                harness.failOriginal = input.get("originalError").getAsBoolean();
                Throwable failure = null;
                try { harness.backend.invoke(TaskManager.class.getDeclaredMethod("removeTask", int.class), new TaskManager(), input.get("task").getAsInt()); }
                catch (Throwable caught) { failure = caught; }
                result.add("trace", harness.trace);
                result.addProperty("finalUid", harness.callerUid);
                result.addProperty("errorPreserved", failure == harness.originalFailure);
            }
            return result;
        }
    }

    static final class Harness implements AutoCloseable, WindowPrivilegesModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final JsonArray trace = new JsonArray();
        final RuntimeException originalFailure = new IllegalStateException("OEM removal failure");
        final ModuleRuntime runtime;
        int nativeUid = 10064;
        int callerUid = 10064;
        int originals;
        boolean stock;
        boolean failOriginal;

        Harness() { this(true); }
        Harness(boolean start) {
            active.set(this);
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.android.server.wm.ActivityTaskManagerService": return TaskManager.class;
                    case "com.android.server.wm.DisplayWindowSettings": return DisplaySettings.class;
                    case "com.android.server.wm.DisplayContent": return DisplayContent.class;
                    case "com.android.server.input.InputManagerService": return InputManager.class;
                    case "com.android.server.display.DisplayManagerService$BinderService": return DisplayService.class;
                    case "com.android.server.wm.ActivityStackSupervisor": return StackSupervisor.class;
                    case "com.android.server.wm.ActivityRecord": return ActivityRecord.class;
                    case "com.android.server.pm.PackageManagerService": return PackageManager.class;
                    case "android.content.pm.ActivityInfo": return ActivityInfo.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new WindowPrivilegesModule(methods, hooks, this));
            if (!start) return;
            runtime.apply("code", 1, List.of("windows"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("Window privileges not ready", runtime.snapshot().failure);
        }
        @Override public int nativeUid() { return nativeUid; }
        @Override public int callingUid() { return callerUid; }
        @Override public long clearIdentity() { long previous = callerUid; callerUid = 1000; event("clear"); return previous; }
        @Override public void restoreIdentity(long identity) { callerUid = (int) identity; event("restore", identity); }
        @Override public void error(Exception failure) {}
        void event(Object... values) {
            JsonArray event = new JsonArray();
            for (Object value : values) if (value instanceof Number) event.add((Number) value); else event.add(String.valueOf(value));
            trace.add(event);
        }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); active.remove(); }
    }

    static final class TaskManager {
        boolean removeTask(int task) {
            Harness harness = active.get();
            harness.event("remove", task, harness.callerUid);
            if (harness.failOriginal) throw harness.originalFailure;
            return harness.callerUid == 1000;
        }
    }
    static final class DisplaySettings { boolean shouldShowImeLocked(DisplayContent display) { active.get().originals++; return active.get().stock; } }
    static final class DisplayContent {
        final JsonObject options;
        DisplayContent(JsonObject options) { this.options = options; }
        DisplayInfo getDisplayInfo() {
            if (DriveResetContractDriver.flag(options, "metadataError")) throw new IllegalStateException("Display metadata unavailable");
            return new DisplayInfo(options);
        }
        boolean isTrusted() { return !options.has("trusted") || options.get("trusted").getAsBoolean(); }
    }
    static final class DisplayInfo {
        final int type;
        final int ownerUid;
        final String ownerPackageName;
        DisplayInfo(JsonObject options) {
            type = DriveResetContractDriver.integer(options, "type", 5);
            ownerUid = DriveResetContractDriver.integer(options, "uid", 10064);
            ownerPackageName = DriveResetContractDriver.stringValue(options, "package", "ru.big.town.anative");
        }
    }
    static final class InputManager { boolean checkInjectEventsPermission(int pid, int uid) { active.get().originals++; return active.get().stock; } }
    static final class DisplayService { boolean checkCallingPermission(String permission, String operation) { active.get().originals++; return active.get().stock; } }
    static final class StackSupervisor { boolean isCallerAllowedToLaunchOnDisplay(int pid, int uid, int display, ActivityInfo activity) { active.get().originals++; return active.get().stock; } }
    static final class ActivityRecord { boolean canBeLaunchedOnDisplay(int display) { active.get().originals++; return active.get().stock; } }
    static final class PackageManager { boolean hasSystemFeature(String feature, int version) { active.get().originals++; return active.get().stock; } }
    static final class ActivityInfo {}
}
