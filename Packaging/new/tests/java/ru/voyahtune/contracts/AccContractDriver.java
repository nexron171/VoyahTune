package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.AccModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class AccContractDriver {
    static final ThreadLocal<Harness> active = new ThreadLocal<>();

    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        JsonObject options = contract.equals("drive.reset-fallback") ? input.getAsJsonObject("options").deepCopy() : input.deepCopy();
        if (contract.equals("drive.acc-reset")) {
            options.addProperty("energyEnabled", 1);
            options.addProperty("energy", "REV");
            options.addProperty("maintenance", 1);
        }
        try (Harness harness = new Harness(options)) {
            harness.frame(2);
            JsonObject result = new JsonObject();
            result.addProperty("sendCalls", harness.sends.size());
            if (contract.equals("drive.reset-fallback")) {
                result.addProperty("stockBundlePreserved", harness.sends.get(0).values.equals(harness.stock.values));
                return result;
            }
            harness.frame(2);
            DriveResetContractDriver.Bundle packet = harness.sends.get(0);
            JsonArray selected = new JsonArray();
            for (String key : List.of("DRIVING_MODE_SET", "EPS_MODE_SET", "PROP_MODE_SET")) selected.add(packet.getInt(key, -1));
            result.add("selected", selected);
            result.addProperty("energy", packet.getInt("IVI_SOC_MODESET", -1));
            result.addProperty("maintenance", packet.getInt("ASC_MAINTAIN_SWITCH", -1));
            result.addProperty("originalCalls", harness.originals);
            result.addProperty("dispatchCalls", harness.dispatches);
            result.addProperty("stockBundleUnchanged", harness.stock.getInt("DRIVING_MODE_SET", -1) == 1);
            result.addProperty("otherBcmWork", harness.otherBcmWork);
            return result;
        }
    }

    static final class Harness implements AutoCloseable, AccModule.Platform {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final ManualScheduler scheduler = new ManualScheduler();
        final JsonObject options;
        final Component component = new Component();
        final List<DriveResetContractDriver.Bundle> sends = new ArrayList<>();
        final List<Task> tasks = new ArrayList<>();
        final List<Integer> observedAcc = new ArrayList<>();
        final List<Integer> feedback = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final ModuleRuntime runtime;
        final Constructor<Task> constructor;
        DriveResetContractDriver.Bundle stock;
        Map<State, Integer> executed;
        int acc;
        int providerAcc;
        long revision;
        long cycle;
        int originals;
        int dispatches;
        int completes;
        int caller;
        int taskExecutions;
        boolean otherBcmWork;
        boolean smartSave;
        boolean snapshotFailure;
        boolean supported = true;
        boolean claimAllowed = true;
        boolean dispatchAccepted = true;
        boolean persistFailure;
        String startup = "pending";
        RuntimeException sendFailure;
        Runnable beforeSend;

        Harness(JsonObject options) throws Exception {
            this.options = options;
            acc = DriveResetContractDriver.integer(options, "acc", 0);
            providerAcc = acc;
            smartSave = DriveResetContractDriver.flag(options, "smartSaveEnabled");
            constructor = Task.class.getDeclaredConstructor(Component.class, Base.class);
            backend.constructorBackups.put(constructor, Task.class.getDeclaredMethod("initialize", Component.class, Base.class));
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case AccModule.COMPONENT: return Component.class;
                    case AccModule.BASE: return Base.class;
                    case AccModule.TASK: return Task.class;
                    case "com.qinggan.canbus.VehicleState": return State.class;
                    case "android.os.Bundle": return DriveResetContractDriver.Bundle.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", scheduler, id -> new AccModule(methods, hooks, this));
            runtime.apply("code", 1, List.of("acc"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("ACC not ready", runtime.snapshot().failure);
        }

        Object frame(int value) throws Throwable {
            return within(() -> backend.invoke(Component.class.getDeclaredMethod("onBCM_PEPSChangeData", int[].class, boolean.class), component, new int[] {value}, true));
        }

        Object within(Throwing action) throws Throwable {
            Harness previous = active.get();
            active.set(this);
            try { return action.run(); }
            finally { if (previous == null) active.remove(); else active.set(previous); }
        }

        int request(DriveResetContractDriver.Bundle bundle) throws Throwable {
            return (Integer) within(() -> backend.invoke(Component.class.getDeclaredMethod("setVehicleAndAirConditionBundleState",
                    DriveResetContractDriver.Bundle.class, DriveResetContractDriver.Bundle.class), component, null, bundle));
        }

        boolean executeTask(int index) throws Throwable {
            Task task = tasks.get(index);
            return (Boolean) within(() -> backend.invoke(Task.class.getDeclaredMethod("setVehicleMode", HashMap.class), task, task.values));
        }

        Task task() throws Throwable {
            if (PhoneContractDriver.nativeHooks) return new Task(component, component);
            Task task = new Task();
            HookRegistry.Dispatch dispatch = backend.constructors.get(constructor);
            if (dispatch == null) task.initialize(component, component);
            else dispatch.onCall(new Object[] {task, component, component});
            return task;
        }

        @Override public boolean supports(Object owner) { return supported; }
        @Override public int accountType(Object owner) { return DriveResetContractDriver.integer(options, "guest", 1); }
        @Override public boolean nativeCaller() { return caller == 10; }
        @Override public boolean stockCaller() { return caller == 20; }
        @Override public ru.voyahtune.agent.policy.DriveSelection.Targets selected(Object owner) throws Exception { return DriveResetContractDriver.selection(options); }
        @Override public AccModule.Snapshot snapshot(Object owner) {
            if (snapshotFailure) throw new IllegalStateException("Snapshot unavailable");
            return new AccModule.Snapshot(providerAcc, revision, cycle, smartSave, DriveResetContractDriver.stringValue(options, "energy", "EV"),
                    DriveResetContractDriver.integer(options, "forcedEv", 0) == 1, startup);
        }
        @Override public AccModule.Snapshot observeAcc(Object owner, int value) {
            observedAcc.add(value);
            if (providerAcc != 2 && value == 2) { cycle++; startup = "pending"; }
            providerAcc = value;
            return snapshot(owner);
        }
        @Override public boolean dispatchSettings(Object owner) { dispatches++; return dispatchAccepted; }
        @Override public void persist(Object owner, int drive, int energy) {
            if (persistFailure) throw new IllegalStateException("Persistence unavailable");
            String mode = ru.voyahtune.agent.policy.DriveSelection.driveName(drive);
            String power = ru.voyahtune.agent.policy.DriveSelection.energyName(energy);
            if (mode != null) options.addProperty("mode", mode);
            if (power != null) options.addProperty("energy", power);
            revision++;
            startup = "selected";
        }
        @Override public boolean claim(Object owner, long expected) { return claimAllowed && revision == expected && startup.equals("pending"); }
        @Override public void complete(Object owner, long expected, boolean accepted) { completes++; startup = accepted ? "submitted" : "failed"; }
        @Override public Object discover() { return component; }
        @Override public int submit(Object owner, Object bundle) throws Exception {
            try { return request((DriveResetContractDriver.Bundle) bundle); }
            catch (Exception failure) { throw failure; }
            catch (Throwable failure) { throw new AssertionError(failure); }
        }
        @Override public void error(String operation, Exception failure) {}
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }

        void readiness(long millis) throws Throwable { within(() -> { scheduler.advance(millis); return null; }); }
    }

    @FunctionalInterface interface Throwing { Object run() throws Throwable; }
    enum State { DRIVING_MODE_SET, EPS_MODE_SET, PROP_MODE_SET, IVI_SOC_MODESET, HUM_ENERGY_PTREGEN_LEVL, ASC_MAINTAIN_SWITCH, VEHICLE_AMBIENT_LIGHT_COLOR, IVI_PRIVACY_SETTING, CHILD_LOCK_CONTROL }
    static class Base {
        void onVehicleStateChanged(State state, int value) { if (state == State.DRIVING_MODE_SET) active.get().feedback.add(value); }
    }
    static final class Component extends Base {
        int getAccStatus() { return active.get().acc; }
        int onBCM_PEPSChangeData(int[] data, boolean notify) throws Throwable {
            Harness harness = active.get();
            harness.originals++;
            if (harness.beforeSend != null) harness.beforeSend.run();
            if ((data[0] & 7) == 2 && harness.acc != 2) {
                boolean guest = harness.accountType(this) == 1;
                harness.stock = packet(Map.of("DRIVING_MODE_SET", guest ? 1 : 2, "EPS_MODE_SET", 2, "PROP_MODE_SET", guest ? 1 : 2, "HUM_ENERGY_PTREGEN_LEVL", 4));
                harness.request(harness.stock);
                harness.backend.invoke(Base.class.getDeclaredMethod("onVehicleStateChanged", State.class, int.class), this, State.DRIVING_MODE_SET, 1);
                harness.backend.invoke(Component.class.getDeclaredMethod("setVehicleState", State.class, int.class), this, State.VEHICLE_AMBIENT_LIGHT_COLOR, 19);
                harness.acc = 2;
            } else if ((data[0] & 7) <= 1) harness.acc = 0;
            harness.otherBcmWork = true;
            return 123;
        }
        int setVehicleAndAirConditionBundleState(DriveResetContractDriver.Bundle air, DriveResetContractDriver.Bundle vehicle) throws Throwable {
            Harness harness = active.get();
            harness.sends.add(vehicle);
            if (harness.sendFailure != null) throw harness.sendFailure;
            Task task = harness.task();
            for (Map.Entry<String, Object> entry : vehicle.values.entrySet()) {
                try { task.values.put(State.valueOf(entry.getKey()), (Integer) entry.getValue()); }
                catch (IllegalArgumentException unused) { /* OEM ignores integration metadata. */ }
            }
            harness.tasks.add(task);
            return 0;
        }
        int setVehicleState(State state, int value) { if (state == State.VEHICLE_AMBIENT_LIGHT_COLOR) active.get().colors.add(value); return 0; }
    }
    static final class Task {
        HashMap<State, Integer> values;
        Task() {}
        Task(Component owner, Base base) { initialize(owner, base); }
        void initialize(Component owner, Base base) { values = new HashMap<>(); }
        boolean setVehicleMode(HashMap<State, Integer> value) { active.get().taskExecutions++; active.get().executed = value; return true; }
    }
    static DriveResetContractDriver.Bundle packet(Map<String, Object> values) {
        DriveResetContractDriver.Bundle bundle = new DriveResetContractDriver.Bundle();
        bundle.values.putAll(values);
        return bundle;
    }
}
