package ru.voyahtune.contracts;

import ru.voyahtune.agent.policy.IntegerSetting;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class ClusterModuleTest {
    public static void main(String[] arguments) throws Throwable { verify(); System.out.println("ClusterModuleTest: passed"); }

    static void verify() throws Throwable {
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(false, false, false)) {
            harness.flush();
            require(harness.backend.installed.size() == 3, "Live service writer not discovered");
            harness.send(ClusterContractDriver.GuideState.GUIDING);
            require(harness.writes.get(0).state == ClusterContractDriver.GuideState.GUIDING, "Default enabled replaced OEM guidance");
            harness.enabled = false;
            harness.changed.run();
            harness.flush();
            stopped(harness.writes.get(1));
            harness.send(ClusterContractDriver.GuideState.GUIDING);
            stopped(harness.writes.get(2));
            require(harness.sources.get(1).info.state == ClusterContractDriver.GuideState.GUIDING, "Writer mutated source NaviInfo");
            harness.enabled = true;
            harness.changed.run();
            harness.enabled = false;
            harness.changed.run();
            harness.enabled = true;
            harness.changed.run();
            harness.flush();
            require(harness.writes.size() == 3, "Queued clear erased re-enabled fresh guidance");
            harness.transact(999);
            harness.transact(999);
            require(harness.backend.installed.size() == 3, "Repeated Binder calls duplicated writer hook");
            harness.runtime.close();
            int writes = harness.writes.size();
            harness.changed.run();
            harness.flush();
            harness.scheduler.advance(30000);
            require(harness.writes.size() == writes && harness.closedObservers == 1 && harness.backend.installed.isEmpty(), "Cluster cleanup left observer/timers/hooks");
        }
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(true, false, false)) {
            harness.enabled = false;
            harness.changed.run();
            harness.flush();
            harness.transact(105);
            require(harness.writes.size() == 2, "Late Binder did not clear captured and incoming guidance");
            for (ClusterContractDriver.Info info : harness.writes) stopped(info);
            harness.enabled = true;
            harness.changed.run();
            harness.transact(105);
            require(harness.writes.get(2).state == ClusterContractDriver.GuideState.GUIDING, "Fresh Binder guidance not restored");
        }
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(false, true, false)) {
            harness.enabled = false;
            harness.changed.run();
            harness.flush();
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY && harness.writes.size() == 1, "Missing formatter disabled supported writer");
            stopped(harness.writes.get(0));
        }
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(false, false, true)) {
            harness.flush();
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.READY && harness.backend.installed.size() == 1, "Missing writer disabled supported units");
        }
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(false, true, true)) {
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.FAILED && harness.backend.installed.isEmpty(), "Unsupported cluster reported ready");
        }
        try (ClusterContractDriver.Harness harness = new ClusterContractDriver.Harness(true, false, false)) {
            harness.backend.accept = method -> {
                if (method.getName().equals("updateNaviInfo")) throw new IllegalStateException("Uncertain native installation");
                return true;
            };
            harness.transact(999);
            require(harness.hooks.requiresRestart(), "Dynamic writer uncertainty did not quarantine hook registry");
        }
        require(IntegerSetting.matches("\ufeff -000x", 0) && IntegerSetting.matches(" +1enabled", 1)
                && !IntegerSetting.matches("01", 0) && !IntegerSetting.matches("disabled", 0)
                && !IntegerSetting.matches("99999999999999999999999999999999", 1), "OEM integer-prefix settings parsing changed");
    }

    private static void stopped(ClusterContractDriver.Info info) {
        require(info.state == ClusterContractDriver.GuideState.MAN_STOP && !info.display && info.icon == 0 && info.distance == 0
                && info.currentRoad.isEmpty() && info.nextRoad.isEmpty() && info.destination.isEmpty(), "Stop record retained stale maneuver fields");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
