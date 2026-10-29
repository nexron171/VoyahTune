package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class WindowSystemPolicyTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("WindowSystemPolicyTest: passed");
    }

    static void verify() throws Throwable {
        privileges();
        memory();
    }

    private static void privileges() throws Throwable {
        try (WindowPrivilegesContractDriver.Harness harness = new WindowPrivilegesContractDriver.Harness()) {
            Method input = WindowPrivilegesContractDriver.InputManager.class.getDeclaredMethod("checkInjectEventsPermission", int.class, int.class);
            Object manager = new WindowPrivilegesContractDriver.InputManager();
            require((Boolean) harness.backend.invoke(input, manager, 42, harness.nativeUid), "Native input denied");
            require(!(Boolean) harness.backend.invoke(input, manager, 42, 10199), "Foreign input allowed");
            Method permission = WindowPrivilegesContractDriver.DisplayService.class.getDeclaredMethod("checkCallingPermission", String.class, String.class);
            Object display = new WindowPrivilegesContractDriver.DisplayService();
            for (String allowed : new String[] {"ADD_TRUSTED_DISPLAY", "INTERNAL_SYSTEM_WINDOW"}) {
                require((Boolean) harness.backend.invoke(permission, display, "android.permission." + allowed, "operation"), "Native display permission denied");
            }
            require(!(Boolean) harness.backend.invoke(permission, display, "android.permission.WRITE_SECURE_SETTINGS", "operation"), "Unrelated permission allowed");
            harness.callerUid = 10199;
            require(!(Boolean) harness.backend.invoke(permission, display, "android.permission.ADD_TRUSTED_DISPLAY", "operation"), "Foreign display permission allowed");
            Method launch = WindowPrivilegesContractDriver.StackSupervisor.class.getDeclaredMethod("isCallerAllowedToLaunchOnDisplay", int.class, int.class, int.class, WindowPrivilegesContractDriver.ActivityInfo.class);
            Object stack = new WindowPrivilegesContractDriver.StackSupervisor();
            require((Boolean) harness.backend.invoke(launch, stack, 42, harness.nativeUid, 4, null), "Native launch denied");
            require(!(Boolean) harness.backend.invoke(launch, stack, 42, 10199, 4, null), "Foreign launch allowed");
            Method activity = WindowPrivilegesContractDriver.ActivityRecord.class.getDeclaredMethod("canBeLaunchedOnDisplay", int.class);
            Object record = new WindowPrivilegesContractDriver.ActivityRecord();
            require((Boolean) harness.backend.invoke(activity, record, 2), "Secondary display rejected");
            require(!(Boolean) harness.backend.invoke(activity, record, 0), "Primary display policy replaced");
            Method feature = WindowPrivilegesContractDriver.PackageManager.class.getDeclaredMethod("hasSystemFeature", String.class, int.class);
            Object packages = new WindowPrivilegesContractDriver.PackageManager();
            require((Boolean) harness.backend.invoke(feature, packages, "android.software.activities_on_secondary_displays", 0), "Secondary activity feature absent");
            require(!(Boolean) harness.backend.invoke(feature, packages, "android.hardware.telephony", 0), "Unrelated feature changed");
            harness.nativeUid = -1;
            require(!(Boolean) harness.backend.invoke(input, manager, 42, -1), "Unresolved UID granted input");
            harness.runtime.close();
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.CLOSED && harness.backend.installed.isEmpty(), "Privilege cleanup left hooks");
        }
    }

    private static void memory() throws Throwable {
        try (WindowMemoryContractDriver.Harness harness = new WindowMemoryContractDriver.Harness()) {
            WindowMemoryContractDriver.ProcessRecord process = new WindowMemoryContractDriver.ProcessRecord("ru.big.town.restoremode", 200, true);
            WindowMemoryContractDriver.OomAdjuster adjuster = new WindowMemoryContractDriver.OomAdjuster();
            Method apply = adjuster.getClass().getDeclaredMethod("applyOomAdjLocked", process.getClass(), boolean.class, long.class, long.class);
            WindowMemoryContractDriver.ActivityRecord activity = new WindowMemoryContractDriver.ActivityRecord(process.windows, "com.shilapi.xcertplay.CarPlayHostActivity");
            Method destroy = activity.getClass().getDeclaredMethod("isDestroyable");
            harness.backend.invoke(apply, adjuster, process, true, 1L, 1L);
            require(process.curAdj == 0 && adjuster.calls == 1, "Session priority or stock OOM call changed");
            activity.finishing = true;
            require((Boolean) harness.backend.invoke(destroy, activity), "Finishing activity protected");
            activity.finishing = false;
            activity.app = null;
            require((Boolean) harness.backend.invoke(destroy, activity), "Detached activity protected");
            activity.app = process.windows;
            process.service.isForeground = false;
            process.curAdj = 150;
            harness.backend.invoke(apply, adjuster, process, true, 2L, 2L);
            require(process.curAdj == 150 && (Boolean) harness.backend.invoke(destroy, activity), "Ended session retained protection");
            process.service.isForeground = true;
            process.curAdj = -100;
            adjuster.failure = new IllegalStateException("Original OOM failure");
            Throwable failure = null;
            try { harness.backend.invoke(apply, adjuster, process, true, 3L, 3L); }
            catch (Throwable caught) { failure = caught; }
            require(failure == adjuster.failure && adjuster.calls == 3 && process.curAdj == -100, "OEM OOM failure replayed or high priority overwritten");
            harness.runtime.close();
            require(harness.recomputations == 1 && harness.backend.installed.isEmpty(), "Cleanup did not recompute once after removing hooks");
            require((Boolean) harness.backend.invoke(destroy, activity), "Closed memory module retained protection");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
