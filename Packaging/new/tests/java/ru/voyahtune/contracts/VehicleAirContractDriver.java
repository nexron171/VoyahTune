package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.VehicleAirModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class VehicleAirContractDriver {
    static final ThreadLocal<Harness> active = new ThreadLocal<>();

    static JsonObject execute(JsonObject input) throws Throwable {
        try (Harness harness = new Harness(input)) {
            Throwable failure = null;
            try {
                harness.menu(3, input.get("mode").getAsInt());
            } catch (Throwable caught) {
                failure = caught;
            }
            JsonObject result = new JsonObject();
            result.add("savedModes", harness.saved);
            result.addProperty("sendCalls", harness.bus.sends);
            result.addProperty("error", failure == null ? null : failure.getMessage());
            return result;
        }
    }

    static final class Harness implements AutoCloseable {
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final CanBus bus = new CanBus();
        final JsonArray saved = new JsonArray();
        final ModuleRuntime runtime;
        final String failure;

        Harness(JsonObject input) {
            failure = input.has("failure") ? input.get("failure").getAsString() : "";
            if (failure.equals("binder-error")) bus.failure = new IllegalStateException("Binder failed");
            if (failure.equals("rejected")) bus.result = -1;
            MethodAccess methods = new MethodAccess(name -> {
                switch (name) {
                    case "com.qinggan.canbus.CanBusManager": return CanBus.class;
                    case "com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$1": return Handler.class;
                    case "com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$2": return Confirm.class;
                    case "android.os.Bundle": return Bundle.class;
                    case "android.os.Message": return Message.class;
                    case "android.view.View": return View.class;
                    default: return Class.forName(name);
                }
            });
            runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new VehicleAirModule(methods, hooks, mode -> {
                if (failure.equals("provider-error")) throw new IllegalStateException("Provider unavailable");
                saved.add(mode);
            }));
            runtime.apply("code", 1, List.of("vehicle-air"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("VehicleAir not ready", runtime.snapshot().failure);
        }

        void menu(int what, int mode) throws Throwable {
            Harness previous = active.get();
            active.set(this);
            try {
                backend.invoke(Handler.class.getDeclaredMethod("handleMessage", Message.class), new Handler(), new Message(what, mode));
            } finally {
                if (previous == null) active.remove(); else active.set(previous);
            }
        }

        void sendMode(int mode) throws Throwable {
            backend.invoke(CanBus.class.getDeclaredMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class), bus, null, new Bundle(mode));
            backend.invoke(CanBus.class.getDeclaredMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class), bus, null, new Bundle(null));
        }

        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static final class CanBus {
        int sends;
        int result;
        RuntimeException failure;
        int setVehicleAndAirConditionBundleState(Bundle air, Bundle vehicle) {
            sends++;
            if (failure != null) throw failure;
            return result;
        }
    }

    static final class Handler {
        void handleMessage(Message message) throws Throwable { active.get().sendMode(message.mode); }
    }

    static final class Confirm {
        void onClick(View view) throws Throwable { active.get().sendMode(1); }
    }

    static final class Message {
        int what;
        int mode;
        Message(int what, int mode) { this.what = what; this.mode = mode; }
    }

    static final class Bundle {
        final Map<String, Integer> values = new HashMap<>();
        Bundle(Integer mode) { if (mode != null) values.put("DRIVING_MODE_SET", mode); }
        int getInt(String key, int fallback) { return values.getOrDefault(key, fallback); }
    }

    static final class View {}
}
