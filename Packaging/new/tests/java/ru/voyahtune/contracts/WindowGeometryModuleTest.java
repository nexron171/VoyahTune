package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class WindowGeometryModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("WindowGeometryModuleTest: passed");
    }

    static void verify() throws Throwable {
        try (WindowGeometryContractDriver.Harness harness = new WindowGeometryContractDriver.Harness()) {
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING, "Deferred geometry reported ready");
            harness.scheduler.advance(0);
            require(harness.backend.installed.size() == 2, "Hot hooks installed before stabilization");
            int reads = harness.reads;
            harness.events.reload();
            harness.events.reload();
            harness.events.reload();
            harness.scheduler.advance(50);
            require(harness.reads == reads + 1, "Reload burst did not coalesce snapshots");
            harness.runtime.apply("code", 2, List.of("windows"), Map.of());
            harness.scheduler.advance(949);
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING && harness.backend.installed.size() == 2, "Configuration shortened stabilization");
            harness.scheduler.advance(1);
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY && harness.backend.installed.size() == 4, "Geometry did not become ready");
            harness.events.screen(false);
            harness.scheduler.advance(0);
            require(harness.backend.installed.size() == 2, "Screen off retained hot hooks");
            harness.events.screen(true);
            harness.scheduler.advance(0);
            harness.events.screen(false);
            harness.scheduler.advance(10000);
            require(harness.backend.installed.size() == 2 && harness.scheduler.activeTasks() == 0, "Stale wake task revived hooks");
            harness.events.screen(true);
            harness.scheduler.advance(4999);
            require(harness.backend.installed.size() == 2, "Rapid wake stabilization shortened");
            harness.scheduler.advance(1);
            require(harness.backend.installed.size() == 4, "Rapid wake did not restore hot hooks");
            harness.runtime.close();
            require(harness.backend.installed.isEmpty() && harness.receivers == 0 && harness.scheduler.activeTasks() == 0, "Geometry cleanup left resources");
        }
        try (WindowGeometryContractDriver.Harness harness = new WindowGeometryContractDriver.Harness()) {
            harness.scheduler.advance(1000);
            harness.settings.put("voyahtune_dpi_packages", "org.app");
            harness.settings.put("voyahtune_dpi_org.app", "240");
            harness.reload();
            WindowGeometryContractDriver.ActivityRecord activity = new WindowGeometryContractDriver.ActivityRecord();
            Method ensure = activity.getClass().getDeclaredMethod("ensureActivityConfiguration", int.class, boolean.class, boolean.class);
            int reads = harness.reads;
            require((Boolean) harness.backend.invoke(ensure, activity, 1, true, false), "Stock configuration result changed");
            require(activity.originals == 1 && activity.task.configuration.densityDpi == 240 && activity.task.configuration.bounds == 123, "Requested density overwrote resolved geometry");
            WindowGeometryContractDriver.DisplayContent destination = new WindowGeometryContractDriver.DisplayContent();
            destination.id = 1;
            Method move = activity.getClass().getDeclaredMethod("onDisplayChanged", destination.getClass());
            int traversals = harness.traversals;
            harness.backend.invoke(move, activity, destination);
            harness.scheduler.advance(0);
            require(activity.originals == 2 && activity.display == 1 && harness.traversals == traversals + 1, "Physical reparent did not replay layout after stock move");
            destination.id = 7;
            harness.backend.invoke(move, activity, destination);
            harness.scheduler.advance(0);
            require(activity.originals == 3 && harness.traversals == traversals + 1 && harness.reads == reads, "Virtual reparent or hot callback read settings");
            harness.settings.remove("voyahtune_dpi_org.app");
            harness.settings.remove("voyahtune_dpi_packages");
            harness.reload();
            require(activity.task.configuration.densityDpi == 0 && activity.task.configuration.bounds == 123, "Removed density policy retained override");
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
