package ru.voyahtune.contracts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import ru.voyahtune.agent.OriginalCall;
import ru.voyahtune.agent.policy.AirQualityPublisher;
import ru.voyahtune.agent.policy.NavigationSourcePolicy;

public final class ContractDriver {
    public static void main(String[] arguments) throws Throwable {
        try {
            run(arguments);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            throw failure;
        }
    }

    private static void run(String[] arguments) throws Throwable {
        if (arguments.length != 0) {
            if (arguments.length != 2 || !arguments[0].equals("--native-library")) {
                throw new IllegalArgumentException("Expected --native-library PATH");
            }
            NativeTestBootstrap.load(arguments[1]);
            PhoneContractDriver.nativeHooks = true;
            NativeHookGcTest.verify();
            nativeCheckpoint("NativeHookGcTest");
            ClassInitializerTest.verify();
            nativeCheckpoint("ClassInitializerTest");
            String contractDex = System.getenv("VOYAHTUNE_CONTRACT_DEX");
            if (contractDex != null) DexCallGraphTest.verify(java.nio.file.Paths.get(contractDex));
            ru.voyahtune.agent.HookRegistryTest.verify(true);
            nativeCheckpoint("ru.voyahtune.agent.HookRegistryTest");
            ApolloModuleTest.verify();
            nativeCheckpoint("ApolloModuleTest");
            MultidisplayModuleTest.verify();
            nativeCheckpoint("MultidisplayModuleTest");
            ru.voyahtune.agent.ModuleListenerTest.verify();
            nativeCheckpoint("ru.voyahtune.agent.ModuleListenerTest");
            ru.voyahtune.agent.ConstructorHookTest.verify();
            nativeCheckpoint("ru.voyahtune.agent.ConstructorHookTest");
            VehicleAirModuleTest.verify();
            nativeCheckpoint("VehicleAirModuleTest");
            DriveResetModuleTest.verify();
            nativeCheckpoint("DriveResetModuleTest");
            AccModuleTest.verify();
            nativeCheckpoint("AccModuleTest");
            NavigationSourceModuleTest.verify();
            nativeCheckpoint("NavigationSourceModuleTest");
            StatusBarModuleTest.verify();
            nativeCheckpoint("StatusBarModuleTest");
            RadioControllersTest.verify();
            nativeCheckpoint("RadioControllersTest");
            RdsTuneOwnershipTest.verify();
            nativeCheckpoint("RdsTuneOwnershipTest");
            RdsModuleTest.verify();
            nativeCheckpoint("RdsModuleTest");
            ClusterModuleTest.verify();
            nativeCheckpoint("ClusterModuleTest");
            ClusterCardControllerTest.verify();
            nativeCheckpoint("ClusterCardControllerTest");
            ModuleHealthTest.verify();
            nativeCheckpoint("ModuleHealthTest");
            WindowSystemPolicyTest.verify();
            nativeCheckpoint("WindowSystemPolicyTest");
            WindowWifiModuleTest.verify();
            nativeCheckpoint("WindowWifiModuleTest");
            ModuleContentionTest.verify();
            nativeCheckpoint("ModuleContentionTest");
            WindowLayoutTest.verify();
            nativeCheckpoint("WindowLayoutTest");
            WindowGeometryModuleTest.verify();
            nativeCheckpoint("WindowGeometryModuleTest");
            ClientGeometryModuleTest.verify();
            nativeCheckpoint("ClientGeometryModuleTest");
            LauncherCatalogTest.verify();
            nativeCheckpoint("LauncherCatalogTest");
            SystemServerClassesTest.verify();
            nativeCheckpoint("SystemServerClassesTest");
            LauncherPresentationTest.verify();
            nativeCheckpoint("LauncherPresentationTest");
            LauncherVisibilityTest.verify();
            nativeCheckpoint("LauncherVisibilityTest");
            ViewOwnershipTest.verify();
            nativeCheckpoint("ViewOwnershipTest");
            LauncherDockModuleTest.verify();
            nativeCheckpoint("LauncherDockModuleTest");
            LauncherAllAppsModuleTest.verify();
            nativeCheckpoint("LauncherAllAppsModuleTest");
            ru.voyahtune.agent.ModuleTimerTest.verify();
            nativeCheckpoint("ru.voyahtune.agent.ModuleTimerTest");
            ru.voyahtune.agent.DriveSelectionTest.verify();
            nativeCheckpoint("ru.voyahtune.agent.DriveSelectionTest");
        }
        JsonObject request = JsonParser.parseReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray results = new JsonArray();
        request.getAsJsonArray("cases").forEach(value -> {
            JsonObject testCase = value.getAsJsonObject();
            JsonObject result = new JsonObject();
            result.addProperty("id", testCase.get("id").getAsString());
            try {
                result.add("observed", execute(
                        testCase.get("contract").getAsString(), testCase.getAsJsonObject("input")));
            } catch (Throwable failure) {
                result.addProperty("error", failure.getClass().getName() + ": " + failure.getMessage());
            }
            results.add(result);
        });
        JsonObject response = new JsonObject();
        response.addProperty("schema", 1);
        response.add("results", results);
        Gson serializer = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
        System.out.print(serializer.toJson(response));
    }

    private static void nativeCheckpoint(String name) {
        if ("1".equals(System.getenv("VOYAHTUNE_ART_GC_CHECKPOINTS"))) {
            System.err.println("Native test completed: " + name);
            System.gc();
        }
    }

    private static JsonObject execute(String contract, JsonObject input) throws Throwable {
        switch (contract) {
            case "windows.memory-protection":
                return WindowMemoryContractDriver.execute(input);
            case "windows.bootstrap":
                return WindowBootstrapContractDriver.execute(input);
            case "geometry.window-state":
                return WindowGeometryContractDriver.execute(input);
            case "geometry.lifecycle":
                return GeometryLifecycleContractDriver.execute(input);
            case "launcher.all-apps":
                return LauncherCatalogContractDriver.execute(input);
            case "launcher.navigation-action":
                return LauncherNavigationContractDriver.execute(input);
            case "launcher.icons":
                return LauncherPresentationContractDriver.execute(input);
            case "launcher.chrome":
                return LauncherDockContractDriver.execute(input);
            case "windows.wifi-lease":
                return WindowWifiContractDriver.execute(input);
            case "windows.task-removal":
            case "windows.ime":
                return WindowPrivilegesContractDriver.execute(contract, input);
            case "cluster.guidance":
            case "cluster.distance":
                return ClusterContractDriver.execute(contract, input);
            case "radio.restore":
            case "radio.widget":
                return RadioContractDriver.execute(contract, input);
            case "statusbar.visibility":
                return StatusBarContractDriver.execute(input);
            case "drive.vehicle-reset":
                return DriveResetContractDriver.execute(contract, input);
            case "drive.reset-fallback":
                if (input.get("module").getAsString().equals("drive-reset")) return DriveResetContractDriver.execute(contract, input);
                return AccContractDriver.execute(contract, input);
            case "drive.acc-reset":
                return AccContractDriver.execute(contract, input);
            case "drive.power-hold":
                return PowerHoldContractDriver.execute(input);
            case "drive.vehicle-air":
                return VehicleAirContractDriver.execute(input);
            case "multidisplay.transfer":
                return MultidisplayContractDriver.execute(input);
            case "keyboard.input-mode":
            case "keyboard.render":
            case "keyboard.toggle-icon":
                return KeyboardContractDriver.execute(contract, input);
            case "apollo.installation":
                return ApolloContractDriver.execute(input);
            case "steering.press":
            case "steering.media-decision":
            case "steering.media-sequence":
                return SteeringContractDriver.execute(contract, input);
            case "phone.number":
            case "phone.dial":
            case "agent.installation":
                return PhoneContractDriver.execute(contract, input);
            case "navigation.policy":
                return navigation(input);
            case "statusbar.air-quality":
                return airQuality(input);
            case "runtime.original-call":
                return originalCall(input);
            case "runtime.modules":
                return ModuleContractDriver.execute(input);
            default:
                throw new UnsupportedOperationException("Java implementation unavailable: " + contract);
        }
    }

    private static JsonObject navigation(JsonObject input) {
        JsonObject result = new JsonObject();
        String value = input.get("value").getAsString();
        switch (input.get("operation").getAsString()) {
            case "yandexIcon":
                result.addProperty("value", NavigationSourcePolicy.yandexIcon(value));
                break;
            case "dgisIcon":
                result.addProperty("value", NavigationSourcePolicy.dgisIcon(value));
                break;
            case "meters":
                result.addProperty("value", NavigationSourcePolicy.meters(value));
                break;
            case "driving":
                result.addProperty("value", NavigationSourcePolicy.driving(value));
                break;
            default:
                throw new IllegalArgumentException("Unknown navigation operation");
        }
        return result;
    }

    private static JsonObject airQuality(JsonObject input) {
        JsonArray writes = new JsonArray();
        String[] saved = {null};
        input.getAsJsonArray("events").forEach(value -> {
            JsonObject event = value.getAsJsonObject();
            AirQualityPublisher.publish(new AirQualityPublisher.Storage() {
                @Override
                public long bootCount() {
                    return event.get("boot").getAsLong();
                }

                @Override
                public String read() {
                    return saved[0];
                }

                @Override
                public void write(String sample) {
                    saved[0] = sample;
                    writes.add(sample);
                }
            }, event.get("value").getAsDouble());
        });
        JsonObject result = new JsonObject();
        result.add("writes", writes);
        return result;
    }

    private static JsonObject originalCall(JsonObject input) throws Throwable {
        OriginalFixture fixture = new OriginalFixture();
        OriginalFixture.staticCalls = 0;
        String kind = input.get("kind").getAsString();
        JsonArray values = input.getAsJsonArray("arguments");
        Method method;
        Object[] arguments;
        switch (kind) {
            case "static":
                method = OriginalFixture.class.getDeclaredMethod("add", int.class, int.class);
                arguments = new Object[] {values.get(0).getAsInt(), values.get(1).getAsInt()};
                break;
            case "instance":
                method = OriginalFixture.class.getDeclaredMethod("increment", int.class);
                arguments = new Object[] {fixture, values.get(0).getAsInt()};
                break;
            case "void":
                method = OriginalFixture.class.getDeclaredMethod("remember", String.class);
                arguments = new Object[] {fixture, values.get(0).getAsString()};
                break;
            case "exception":
                method = OriginalFixture.class.getDeclaredMethod("fail");
                arguments = new Object[] {fixture};
                break;
            case "missing-receiver":
                method = OriginalFixture.class.getDeclaredMethod("increment", int.class);
                arguments = new Object[0];
                break;
            default:
                throw new IllegalArgumentException("Unknown original call scenario");
        }
        Object value = null;
        Throwable failure = null;
        try {
            value = new OriginalCall(method, method).invoke(arguments);
        } catch (Throwable caught) {
            failure = caught;
        }
        Gson serializer = new GsonBuilder().serializeNulls().create();
        JsonObject observed = new JsonObject();
        observed.add("value", serializer.toJsonTree(value));
        observed.addProperty("originalCalls", fixture.calls + OriginalFixture.staticCalls);
        observed.addProperty("error", failure == null ? null : failure.getMessage());
        observed.addProperty("sameException", failure == fixture.failure);
        if (kind.equals("void")) {
            observed.addProperty("remembered", fixture.remembered);
        }
        return observed;
    }

    private static final class OriginalFixture {
        static int staticCalls;
        int calls;
        String remembered;
        final IllegalStateException failure = new IllegalStateException("original-failure");

        private static int add(int first, int second) {
            staticCalls++;
            return first + second;
        }

        private int increment(int value) {
            calls++;
            return value + 5;
        }

        private void remember(String value) {
            calls++;
            remembered = value;
        }

        private void fail() {
            calls++;
            throw failure;
        }
    }
}
