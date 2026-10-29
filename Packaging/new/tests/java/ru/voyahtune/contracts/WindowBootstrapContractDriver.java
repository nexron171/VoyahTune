package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.modules.WindowGeometryModule;
import ru.voyahtune.agent.modules.WindowMemoryProtectionModule;
import ru.voyahtune.agent.modules.WindowPreparation;
import ru.voyahtune.agent.modules.WindowPrivilegesModule;
import ru.voyahtune.agent.modules.WindowWifiModule;
import ru.voyahtune.agent.modules.WindowsModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class WindowBootstrapContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        try (WindowPrivilegesContractDriver.Harness privileges = new WindowPrivilegesContractDriver.Harness(false)) {
            WindowGeometryContractDriver.Harness geometry = new WindowGeometryContractDriver.Harness(privileges.backend, privileges.hooks, false);
            MethodAccess geometryMethods = geometry.methods();
            MethodAccess privilegeMethods = new MethodAccess(name -> {
                switch (name) {
                    case "com.android.server.wm.ActivityTaskManagerService": return WindowPrivilegesContractDriver.TaskManager.class;
                    case "com.android.server.wm.DisplayWindowSettings": return DisplaySettings.class;
                    case "com.android.server.input.InputManagerService": return WindowPrivilegesContractDriver.InputManager.class;
                    case "com.android.server.display.DisplayManagerService$BinderService": return WindowPrivilegesContractDriver.DisplayService.class;
                    case "com.android.server.wm.ActivityStackSupervisor": return WindowPrivilegesContractDriver.StackSupervisor.class;
                    case "com.android.server.pm.PackageManagerService": return WindowPrivilegesContractDriver.PackageManager.class;
                    case "android.content.pm.ActivityInfo": return WindowPrivilegesContractDriver.ActivityInfo.class;
                    default: return geometryMethods.type(name);
                }
            });
            MethodAccess methods = new MethodAccess(name -> {
                if (name.equals("com.android.server.wm.DisplayPolicy") && DriveResetContractDriver.flag(input, "missingRequiredMethod")) return MissingLayout.class;
                return privilegeMethods.type(name);
            });
            WindowsModule module = new WindowsModule(new WindowsModule.Preparation() {
                @Override public long now() { return geometry.scheduler.now(); }
                @Override public void probe() throws Exception {
                    if (geometry.scheduler.now() < DriveResetContractDriver.integer(input, "readyAt", 0)) throw new WindowPreparation.Pending("System services pending");
                }
                @Override public void pause(long millis) { geometry.scheduler.advance(millis); }
            }, new WindowPrivilegesModule(privilegeMethods, privileges.hooks, privileges),
                    new WindowGeometryModule(methods, privileges.hooks, geometry),
                    new WindowMemoryProtectionModule(methods, privileges.hooks, new WindowMemoryProtectionModule.Platform() {
                        @Override public void recompute() {}
                        @Override public void error(Exception failure) {}
                    }), new WindowWifiModule(privileges.hooks, new WindowWifiModule.Platform() {
                        @Override public long bootCount() { return 7; }
                        @Override public long now() { return geometry.scheduler.now(); }
                        @Override public String lease() { return ""; }
                        @Override public WindowWifiModule.Endpoint discover() throws Exception { throw new NoSuchMethodException("Optional Wi-Fi unavailable"); }
                        @Override public void error(Exception failure) {}
                    }));
            int[] installs = {0};
            privileges.backend.accept = target -> ++installs[0] != DriveResetContractDriver.integer(input, "failInstallAt", -1);
            ModuleRuntime runtime = new ModuleRuntime("code", geometry.scheduler, id -> module);
            WindowPreparation preparation = new WindowPreparation(geometry.scheduler::now, () -> module.preflight(Map.of()));
            Runnable[] poll = new Runnable[1];
            WindowPreparation.State[] state = {WindowPreparation.State.PREPARING};
            poll[0] = () -> {
                state[0] = preparation.poll();
                if (state[0] == WindowPreparation.State.PREPARING) geometry.scheduler.schedule(500, poll[0]);
                else if (state[0] == WindowPreparation.State.READY) runtime.apply("code", 1, List.of("windows"), Map.of());
            };
            poll[0].run();
            geometry.scheduler.advance(DriveResetContractDriver.integer(input, "advance", 0));
            boolean failed = state[0] == WindowPreparation.State.FAILED || runtime.snapshot().phase == ModuleRuntime.Phase.FAILED;
            JsonObject result = new JsonObject();
            result.addProperty("state", failed ? "failed" : runtime.snapshot().phase == ModuleRuntime.Phase.READY ? "active" : "preparing");
            result.addProperty("hookCount", privileges.backend.installed.size());
            result.addProperty("receiverCount", geometry.receivers);
            runtime.close();
            return result;
        }
    }

    static final class DisplaySettings { boolean shouldShowImeLocked(WindowGeometryContractDriver.DisplayContent display) { return false; } }
    static final class MissingLayout {}
}
