package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ApolloModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class ApolloContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        String boot = "test-boot";
        Map<String, String> configuration = Map.of("apollo.enabled", input.get("enabled").getAsBoolean() ? "1" : "0",
                "apollo.bootId", input.get("sameBoot").getAsBoolean() ? boot : "old-boot");
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        String missing = input.get("missingClass").isJsonNull() ? null : input.get("missingClass").getAsString();
        MethodAccess methods = methods(missing);
        try (HookRegistry hooks = new HookRegistry(backend);
             ModuleRuntime runtime = new ModuleRuntime("test-code", new ManualScheduler(), id -> new ApolloModule(methods, hooks, boot))) {
            boolean enabled = ApolloModule.allowed(configuration, boot);
            runtime.apply("test-code", 1, enabled ? List.of("apollo") : List.of(), configuration);
            JsonObject result = new JsonObject();
            result.addProperty("state", runtime.snapshot().phase == ModuleRuntime.Phase.FAILED ? "failed"
                    : runtime.snapshot().modules.isEmpty() ? "disabled" : "active");
            result.addProperty("hookCount", backend.installed.size());
            result.addProperty("canCommands", StatusFixture.commands);
            result.addProperty("layoutOverrides", backend.installed.keySet().stream()
                    .filter(method -> method.getDeclaringClass() == LayoutFixture.class).count());
            return result;
        }
    }

    static MethodAccess methods(String missing) {
        return new MethodAccess(name -> {
            if (missing != null && name.endsWith("." + missing)) {
                throw new ClassNotFoundException(name);
            }
            if (name.endsWith(".BaiduProviderUtil")) return ProviderFixture.class;
            if (name.endsWith(".DriveAssistantConfig")) return ConfigFixture.class;
            if (name.endsWith(".DriveAssistantData")) return DataFixture.class;
            if (name.endsWith(".DriveAssistanceAdasStatusManager")) return StatusFixture.class;
            if (name.endsWith(".AppCommonUtils")) return LayoutFixture.class;
            if (name.equals("android.content.Context")) return Object.class;
            return Class.forName(name);
        });
    }

    static final class ProviderFixture {
        static String doQuerySubscribeInfo(Object context) { return "inactive"; }
        static String doQueryNOALearnInfo(Object context, String value) { return "0"; }
    }

    static final class ConfigFixture {
        static boolean isSupportSDB() { return false; }
    }

    static final class DataFixture {
        int getStatusType() { return 0; }
        boolean isShowAIIntelligence() { return false; }
    }

    static final class StatusFixture {
        static int commands;
        boolean getSubscriptionStatus() { return false; }
        boolean getExpireStatus() { return false; }
        int getRemainDay() { return 0; }
        int getLearnStatus() { return 0; }
        void setEntitlement() { commands++; }
    }

    static final class LayoutFixture {
        static boolean is97X() { return true; }
    }
}
