package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AccModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("AccModuleTest: passed");
    }

    static void verify() throws Throwable {
        profilesAndScopes();
        startupAndCycles();
        automaticTaskOwnership();
        temporaryEnergy();
    }

    private static void profilesAndScopes() throws Throwable {
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(new JsonObject())) {
            harness.frame(2);
            require(harness.feedback.equals(List.of(3)) && harness.colors.equals(List.of(64)), "Guest feedback/color did not match selected SPORT");
            harness.request(AccContractDriver.packet(Map.of("DRIVING_MODE_SET", 1)));
            require(harness.sends.get(1).getInt("DRIVING_MODE_SET", -1) == 1, "Parser scope leaked to unrelated setter");
            harness.supported = false;
            harness.frame(0);
            harness.frame(2);
            require(harness.sends.get(2) == harness.stock || harness.sends.get(2).getInt("DRIVING_MODE_SET", -1) == 1, "Unsupported model restored modes");
        }
        JsonObject nonGuest = new JsonObject();
        nonGuest.addProperty("guest", 2);
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(nonGuest)) {
            harness.frame(2);
            require(harness.sends.get(0).getInt("DRIVING_MODE_SET", -1) == 2 && harness.feedback.equals(List.of(1)), "Non-guest parser was rewritten");
        }
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(new JsonObject())) {
            RuntimeException failure = new IllegalStateException("owned-Binder-failure");
            harness.sendFailure = failure;
            try { harness.frame(2); throw new AssertionError("Parser lost Binder failure"); }
            catch (RuntimeException caught) { require(caught == failure && harness.sends.size() == 1, "Parser replayed failed Binder call"); }
            harness.sendFailure = null;
            harness.request(AccContractDriver.packet(Map.of("DRIVING_MODE_SET", 1)));
            require(harness.sends.get(1).getInt("DRIVING_MODE_SET", -1) == 1, "Failed parser leaked automatic scope");
            harness.caller = 10;
            harness.persistFailure = true;
            require(harness.request(AccContractDriver.packet(Map.of("IVI_SOC_MODESET", 3))) == 0 && harness.sends.size() == 3, "Persistence error affected OEM send");
        }
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(new JsonObject())) {
            harness.beforeSend = () -> {
                harness.beforeSend = () -> {
                    try { harness.request(AccContractDriver.packet(Map.of("DRIVING_MODE_SET", 1, "EPS_MODE_SET", 2, "PROP_MODE_SET", 1))); }
                    catch (Throwable failure) { throw new AssertionError(failure); }
                };
                try { harness.frame(0); }
                catch (Throwable failure) { throw new AssertionError(failure); }
                harness.beforeSend = null;
            };
            harness.frame(2);
            require(harness.sends.get(0).getInt("DRIVING_MODE_SET", -1) == 1 && harness.sends.get(1).getInt("DRIVING_MODE_SET", -1) == 3,
                    "Nested non-ACC parser inherited automatic scope or failed to restore outer scope");
        }
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(new JsonObject())) {
            harness.request(AccContractDriver.packet(Map.of("DRIVING_MODE_SET", 2, "__vt_auto", true, "__vt_user", true)));
            require(harness.revision == 0 && harness.sends.get(0).getInt("DRIVING_MODE_SET", -1) == 2
                    && !harness.sends.get(0).containsKey("__vt_auto"), "Untrusted caller forged selection ownership");
        }
    }

    private static void startupAndCycles() throws Throwable {
        for (int off : new int[] {0, 1}) {
            try (AccContractDriver.Harness harness = new AccContractDriver.Harness(energyOptions())) {
                for (int cycle = 1; cycle <= 2; cycle++) {
                    harness.frame(off);
                    harness.frame(2);
                    harness.readiness(cycle == 1 ? 0 : 5000);
                    int sends = harness.sends.size();
                    require(harness.cycle == cycle && sends == cycle * 2 && harness.completes == cycle && harness.dispatches == cycle, "ACC cycle ownership lost");
                    harness.frame(2);
                    harness.readiness(5000);
                    require(harness.sends.size() == sends && harness.dispatches == cycle, "Repeated ON/readiness replayed modes/settings");
                }
                harness.runtime.apply("code", 2, List.of("acc"), Map.of());
                harness.frame(0);
                harness.frame(2);
                harness.readiness(0);
                require(harness.sends.size() == 6 && harness.dispatches == 3, "Readiness timer lost after revision change");
                harness.runtime.close();
                harness.readiness(10000);
                require(harness.sends.size() == 6 && harness.backend.installed.isEmpty() && harness.backend.constructors.isEmpty(), "Disabled ACC retained hooks/timer");
            }
        }
        JsonObject late = energyOptions();
        late.addProperty("acc", 2);
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(late)) {
            harness.sendFailure = new IllegalStateException("Uncertain reply");
            harness.readiness(0);
            harness.sendFailure = null;
            harness.readiness(15000);
            require(harness.sends.size() == 1 && harness.completes == 1 && harness.startup.equals("failed"), "Uncertain bootstrap was replayed");
        }
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(late)) {
            harness.startup = "selected";
            harness.readiness(0);
            require(harness.sends.isEmpty() && harness.dispatches == 1, "Persisted user selection was replaced by startup");
        }
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(late)) {
            harness.claimAllowed = false;
            harness.dispatchAccepted = false;
            harness.readiness(0);
            require(harness.sends.isEmpty() && harness.dispatches == 2, "Unclaimed startup was submitted");
            harness.claimAllowed = true;
            harness.dispatchAccepted = true;
            harness.readiness(5000);
            require(harness.sends.size() == 1 && harness.dispatches == 3 && harness.completes == 1, "Readiness did not recover from declined claim/settings start");
        }
    }

    private static void automaticTaskOwnership() throws Throwable {
        List<Consumer<AccContractDriver.Harness>> changes = List.of(
                harness -> harness.smartSave = true,
                harness -> harness.revision++,
                harness -> harness.cycle++,
                harness -> harness.providerAcc = 0,
                harness -> harness.snapshotFailure = true);
        for (Consumer<AccContractDriver.Harness> change : changes) {
            JsonObject options = energyOptions();
            options.addProperty("acc", 2);
            try (AccContractDriver.Harness harness = new AccContractDriver.Harness(options)) {
                harness.readiness(0);
                require(harness.tasks.get(0).values.get(AccContractDriver.State.IVI_SOC_MODESET) == 4, "Bootstrap fixture did not queue energy");
                change.accept(harness);
                require(harness.executeTask(0) && !harness.executed.containsKey(AccContractDriver.State.IVI_SOC_MODESET)
                        && harness.executed.get(AccContractDriver.State.DRIVING_MODE_SET) == 3, "Stale automatic energy was sent or drive was removed");
                require(harness.tasks.get(0).values.get(AccContractDriver.State.IVI_SOC_MODESET) == 4, "Filtering mutated OEM queued values");
            }
        }
        JsonObject energyOnly = energyOptions();
        energyOnly.addProperty("acc", 2);
        energyOnly.addProperty("enabled", 0);
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(energyOnly)) {
            harness.readiness(0);
            harness.smartSave = true;
            require(!harness.executeTask(0) && harness.taskExecutions == 0, "Empty automatic task reached OEM send");
        }
        for (int caller : new int[] {10, 20}) {
            JsonObject options = energyOptions();
            options.addProperty("acc", 2);
            options.addProperty("smartSaveEnabled", true);
            try (AccContractDriver.Harness harness = new AccContractDriver.Harness(options)) {
                harness.caller = caller;
                harness.request(AccContractDriver.packet(Map.of("DRIVING_MODE_SET", 2, "IVI_SOC_MODESET", 4, "__vt_auto", true)));
                require(!harness.sends.get(0).containsKey("IVI_SOC_MODESET") && harness.revision == 0, "Automatic request stole Smart Save ownership");
                harness.executeTask(0);
                require(!harness.executed.containsKey(AccContractDriver.State.IVI_SOC_MODESET), "Automatic task stole Smart Save ownership");
                harness.request(AccContractDriver.packet(Map.of("IVI_SOC_MODESET", 4, "__vt_user", true)));
                harness.executeTask(1);
                require(harness.executed.get(AccContractDriver.State.IVI_SOC_MODESET) == 4 && harness.revision == 1, "Explicit user Save was filtered");
            }
        }
    }

    private static void temporaryEnergy() throws Throwable {
        List<Consumer<AccContractDriver.Harness>> changes = List.of(
                harness -> harness.smartSave = false,
                harness -> harness.revision++,
                harness -> harness.cycle++,
                harness -> harness.providerAcc = 0,
                harness -> harness.snapshotFailure = true,
                harness -> harness.options.addProperty("forcedEv", 1),
                harness -> harness.options.addProperty("energy", "REV"));
        for (Consumer<AccContractDriver.Harness> change : changes) {
            JsonObject options = energyOptions();
            options.addProperty("acc", 2);
            options.addProperty("smartSaveEnabled", true);
            try (AccContractDriver.Harness harness = new AccContractDriver.Harness(options)) {
                harness.caller = 10;
                require(harness.request(temporary(harness, 2)) == 0 && harness.revision == 0, "Temporary EV was rejected or persisted");
                require(harness.sends.get(0).values.keySet().equals(java.util.Set.of("IVI_SOC_MODESET")), "Private metadata reached OEM");
                change.accept(harness);
                require(!harness.executeTask(0) && harness.taskExecutions == 0, "Stale temporary EV task was sent");
            }
        }
        JsonObject options = energyOptions();
        options.addProperty("acc", 2);
        options.addProperty("smartSaveEnabled", true);
        try (AccContractDriver.Harness harness = new AccContractDriver.Harness(options)) {
            harness.caller = 10;
            for (int energy : new int[] {2, 4, 2}) {
                require(harness.request(temporary(harness, energy)) == 0, "Valid temporary mode rejected");
                harness.executeTask(harness.tasks.size() - 1);
                require(harness.executed.get(AccContractDriver.State.IVI_SOC_MODESET) == energy && harness.revision == 0, "Temporary mode changed or persisted");
            }
            harness.smartSave = false;
            require(harness.request(temporary(harness, 2)) == -1 && harness.sends.size() == 3, "Disabled Smart Save accepted temporary EV");
            require(harness.request(temporary(harness, 4)) == 0, "Disable did not allow immediate SREV restore");
            harness.executeTask(3);
            require(harness.executed.get(AccContractDriver.State.IVI_SOC_MODESET) == 4, "Immediate SREV restore was filtered");
        }
    }

    private static DriveResetContractDriver.Bundle temporary(AccContractDriver.Harness harness, int energy) {
        return AccContractDriver.packet(Map.of("IVI_SOC_MODESET", energy, "__vt_temporary_energy", true,
                "__vt_energy_revision", harness.revision, "__vt_energy_cycle", harness.cycle));
    }

    private static JsonObject energyOptions() {
        JsonObject options = new JsonObject();
        options.addProperty("energyEnabled", 1);
        options.addProperty("energy", "SREV");
        return options;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
