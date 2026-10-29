package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.MultidisplayModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class MultidisplayContractDriver {
    static JsonObject execute(JsonObject input) throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        Service service = new Service();
        service.moveRejected = input.has("moveResult") && !input.get("moveResult").getAsBoolean();
        Platform platform = new Platform(input);
        MethodAccess methods = new MethodAccess(name -> name.equals("com.qinggan.systemservice.multidisplay.MultiDisplayImpl")
                ? Service.class : Class.forName(name));
        try (HookRegistry hooks = new HookRegistry(backend);
             ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new MultidisplayModule(methods, hooks, platform))) {
            runtime.apply("code", 1, List.of("multidisplay"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) throw new AssertionError("Multidisplay module failed", runtime.snapshot().failure);
            backend.invoke(Service.class.getDeclaredMethod("swapActivity"), service);
            JsonObject result = new JsonObject();
            result.add("moves", service.moves);
            result.addProperty("originalCalls", service.swaps);
            return result;
        }
    }

    static class Platform implements MultidisplayModule.Platform {
        final JsonObject input;
        Platform(JsonObject input) { this.input = input; }
        @Override public boolean enabled() { return !input.has("enabled") || input.get("enabled").getAsBoolean(); }
        @Override public boolean supportsTransfer() { return true; }
        @Override public String top(int display, int field) {
            if (input.has("metadataError") && input.get("metadataError").getAsBoolean()) throw new IllegalStateException("OEM metadata failed");
            if (field == 2) return "MainActivity";
            if (input.has("tops")) return input.getAsJsonArray("tops").get(display).getAsString();
            return display == 0 ? "org.maps" : "com.qinggan.app.launcher";
        }
        @Override public AutoCloseable reloadReceiver(Runnable action) { return () -> {}; }
        @Override public void trace(String event, Object[] arguments) {}
    }

    static final class Service {
        int swaps;
        int queries;
        int dialogs;
        int animations;
        RuntimeException queryFailure;
        final JsonArray moves = new JsonArray();
        boolean isWhiteListApp(String packageName) {
            queries++;
            if (queryFailure != null) throw queryFailure;
            return packageName != null && packageName.startsWith("stock.");
        }
        boolean isWhiteListApp(int display, String packageName) { queries++; return false; }
        Boolean isWhiteListApp(Boolean flag, String packageName) { queries++; return false; }
        boolean isWhiteListApp(String packageName, String activityName) { queries++; return false; }
        int showDelayDialog(String reason) { dialogs++; return 7; }
        boolean setEnableActivityAnimation(String packageName, String activityName, boolean enabled) { animations++; return enabled; }
        boolean swapActivity() { swaps++; return true; }
        boolean moveActivity(String packageName, String activityName, boolean reverse) {
            JsonArray move = new JsonArray();
            move.add(packageName);
            move.add(activityName);
            move.add(reverse);
            moves.add(move);
            return !moveRejected;
        }
        boolean moveRejected;
    }
}
