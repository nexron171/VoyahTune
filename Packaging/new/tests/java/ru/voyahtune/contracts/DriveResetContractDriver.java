package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.DriveResetModule;
import ru.voyahtune.agent.policy.DriveSelection;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

final class DriveResetContractDriver {
    private static final ThreadLocal<Harness> active = new ThreadLocal<>();

    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        JsonObject options = contract.equals("drive.reset-fallback") ? input.getAsJsonObject("options") : input;
        try (Harness harness = new Harness(options)) {
            harness.invoke(Memory.class.getDeclaredMethod("resetSettings"), new Memory());
            JsonObject result = new JsonObject();
            result.addProperty("sendCalls", harness.bus.sends);
            if (contract.equals("drive.reset-fallback")) {
                Map<String, Object> outgoing = new HashMap<>(harness.bus.vehicle.values);
                outgoing.remove("__vt_auto");
                result.addProperty("stockBundlePreserved", outgoing.equals(harness.stockValues));
                return result;
            }
            JsonArray selected = new JsonArray();
            for (String key : List.of("DRIVING_MODE_SET", "EPS_MODE_SET", "PROP_MODE_SET")) selected.add(harness.bus.vehicle.getInt(key, -1));
            result.add("selected", selected);
            result.addProperty("originalCalls", harness.originals);
            result.addProperty("ambient", harness.bus.vehicle.getInt("VEHICLE_AMBIENT_LIGHT_COLOR", -1));
            result.addProperty("retain", harness.bus.vehicle.getInt("DRIVING_MODE_RETAIN", -1));
            result.addProperty("airUnchanged", harness.bus.air == harness.air);
            result.addProperty("stockBundleUnchanged", harness.stock.values.equals(harness.stockValues));
            result.addProperty("recoveryPresent", harness.bus.vehicle.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
            return result;
        }
    }

    static final class Harness implements AutoCloseable {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final CanBus bus = new CanBus();
        final Bundle air = new Bundle();
        final Map<String, Object> stockValues = Map.of("DRIVING_MODE_SET", 1, "EPS_MODE_SET", 2, "PROP_MODE_SET", 1,
                "IVI_SOC_MODESET", 1, "HUM_ENERGY_PTREGEN_LEVL", 4, "VEHICLE_AMBIENT_LIGHT_COLOR", 19, "DRIVING_MODE_RETAIN", 1);
        final JsonObject options;
        final ModuleRuntime runtime;
        final Map<String, String> saved = new HashMap<>();
        int originals;
        Bundle stock;
        boolean supported = true;
        Throwable resetFailure;

        Harness(JsonObject options) {
            this(options, target -> true);
        }

        Harness(JsonObject options, java.util.function.Predicate<Method> accepted) {
            this.options = options;
            backend.accept = accepted;
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager": return Memory.class;
                    case "com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment": return Fragment.class;
                    case "com.qinggan.canbus.CanBusManager": return CanBus.class;
                    case "com.qinggan.canbus.VehicleState": return State.class;
                    case "android.os.Bundle": return Bundle.class;
                    case "com.qinggan.app.vehiclesetting.CarSettingActivity": return Activity.class;
                    default: return Class.forName(name);
                }
            });
            DriveResetModule.Platform platform = new DriveResetModule.Platform() {
                @Override public boolean supportsReset() { return supported; }
                @Override public boolean supportsUserSelection() { return supported; }
                @Override public DriveSelection.Targets selected() throws Exception { return selection(options); }
                @Override public void save(String key, String value) { saved.put(key, value); }
                @Override public void energyMenu(Object activity) { ((Activity) activity).updates++; }
                @Override public void optionalBridges(ModuleScope scope, HookRegistry registry) {}
                @Override public void error(String operation, Exception failure) {}
            };
            runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new DriveResetModule(methods, hooks, platform));
            runtime.apply("code", 1, List.of("drive-reset"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("DriveReset not ready", runtime.snapshot().failure);
        }

        Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
            Harness previous = active.get();
            active.set(this);
            try { return backend.invoke(method, receiver, arguments); }
            finally { if (previous == null) active.remove(); else active.set(previous); }
        }

        void reset() throws Throwable {
            originals++;
            if (resetFailure != null) throw resetFailure;
            stock = new Bundle();
            stock.values.putAll(stockValues);
            send(stock);
        }

        void send(Bundle bundle) throws Throwable {
            backend.invoke(CanBus.class.getDeclaredMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class), bus, air, bundle);
        }

        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static DriveSelection.Targets selection(JsonObject options) throws Exception {
        if (flag(options, "queryError")) throw new IllegalStateException("Provider unavailable");
        DriveSelection.Row row = flag(options, "nullCursor") ? null : new DriveSelection.Row() {
            @Override public boolean moveToFirst() { return !flag(options, "empty"); }
            @Override public int columnCount() { return DriveResetContractDriver.integer(options, "columns", 33); }
            @Override public boolean isNull(int column) { return options.has("maintenance") && options.get("maintenance").isJsonNull(); }
            @Override public String string(int column) {
                return column == 0 ? stringValue(options, "mode", "SPORT")
                        : column == 1 ? stringValue(options, "energy", "EV") : stringValue(options, "recycle", "LOW");
            }
            @Override public int integer(int column) {
                switch (column) {
                    case 6: return DriveResetContractDriver.integer(options, "enabled", 1);
                    case 7: return DriveResetContractDriver.integer(options, "recycleEnabled", 0);
                    case 8: return DriveResetContractDriver.integer(options, "energyEnabled", 0);
                    case 19: return DriveResetContractDriver.integer(options, "forcedEv", 0);
                    case 32: return DriveResetContractDriver.integer(options, "maintenance", 0);
                    default: return 0;
                }
            }
            @Override public void close() {}
        };
        return DriveSelection.read(row, () -> "1", (key, account) -> {
            if (flag(options, "individualError")) throw new IllegalStateException("Individual provider unavailable");
            JsonObject values = options.has("individual") ? options.getAsJsonObject("individual") : new JsonObject();
            return values.has(key + account) ? values.get(key + account).getAsString() : null;
        });
    }

    static boolean flag(JsonObject options, String key) { return options.has(key) && options.get(key).getAsBoolean(); }
    static int integer(JsonObject options, String key, int fallback) { return options.has(key) ? options.get(key).getAsInt() : fallback; }
    static String stringValue(JsonObject options, String key, String fallback) { return options.has(key) ? options.get(key).getAsString() : fallback; }

    static final class Bundle {
        final Map<String, Object> values = new HashMap<>();
        Bundle() {}
        Bundle(Bundle source) { values.putAll(source.values); }
        boolean containsKey(String key) { return values.containsKey(key); }
        int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        void putInt(String key, int value) { values.put(key, value); }
        void putBoolean(String key, boolean value) { values.put(key, value); }
        long getLong(String key, long fallback) { return ((Number) values.getOrDefault(key, fallback)).longValue(); }
        boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        void remove(String key) { values.remove(key); }
    }

    enum State { IVI_SOC_MODESET, OTHER }
    static final class Memory {
        void resetSettings() throws Throwable { active.get().reset(); }
        void resetOverseaDriveMode() throws Throwable { active.get().reset(); }
    }
    static final class Fragment {
        void setDriveMode(int mode) throws Throwable { active.get().reset(); }
        void setPowerMode(int mode) throws Throwable { onHintSwitchClick(State.IVI_SOC_MODESET, mode); }
        void onHintSwitchClick(State state, int mode) throws Throwable {
            Harness harness = active.get();
            harness.backend.invoke(CanBus.class.getDeclaredMethod("setVehicleState", State.class, int.class), harness.bus, state, mode);
        }
        void setEnergyRecyclerMode(int mode) { if (active.get().bus.failure != null) throw active.get().bus.failure; }
    }
    static final class CanBus {
        int sends;
        Bundle air;
        Bundle vehicle;
        RuntimeException failure;
        int setVehicleAndAirConditionBundleState(Bundle air, Bundle vehicle) {
            sends++;
            this.air = air;
            this.vehicle = vehicle;
            if (failure != null) throw failure;
            return 77;
        }
        int setVehicleState(State state, int value) {
            sends++;
            if (failure != null) throw failure;
            return 77;
        }
    }
    static final class Activity { int originals; int updates; int onResume() { originals++; return 42; } }
}
