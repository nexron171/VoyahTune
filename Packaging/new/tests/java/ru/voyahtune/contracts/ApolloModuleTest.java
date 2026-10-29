package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ApolloModule;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class ApolloModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("ApolloModuleTest: passed");
    }

    static void verify() throws Throwable {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("doQuerySubscribeInfo", "{\"expireStatus\":\"0\",\"isMqtt\":false,\"remainDays\":\"30\",\"subscriptionStatus\":\"1\"}");
        expected.put("doQueryNOALearnInfo", "1");
        expected.put("isSupportSDB", true);
        expected.put("getStatusType", 1002);
        expected.put("isShowAIIntelligence", true);
        expected.put("getSubscriptionStatus", true);
        expected.put("getExpireStatus", true);
        expected.put("getRemainDay", 30);
        expected.put("getLearnStatus", 1);
        Map<String, String> optIn = Map.of("apollo.enabled", "1", "apollo.bootId", "current-boot");
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        try (HookRegistry hooks = new HookRegistry(backend);
             ModuleRuntime runtime = runtime(backend, hooks)) {
            runtime.apply("code", 1, List.of("apollo"), optIn);
            require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Apollo was not ready");
            List<Method> installed = new ArrayList<>(backend.installed.keySet());
            require(installed.size() == 9, "Apollo getter coverage");
            for (Method method : installed) {
                Object receiver = Modifier.isStatic(method.getModifiers()) ? null : method.getDeclaringClass().getDeclaredConstructor().newInstance();
                Object value = backend.invoke(method, receiver, new Object[method.getParameterCount()]);
                require(expected.get(method.getName()).equals(value), "Incorrect Apollo getter: " + method.getName());
            }
            runtime.apply("code", 1, List.of("apollo"), optIn);
            require(backend.installed.size() == 9, "Repeated plan duplicated hooks");
            runtime.close();
            require(backend.installed.isEmpty(), "Apollo cleanup left hooks");
            require(!(Boolean) backend.invoke(ApolloContractDriver.ConfigFixture.class.getDeclaredMethod("isSupportSDB"), null),
                    "OEM capability was not restored");
            require(ApolloContractDriver.LayoutFixture.is97X(), "Layout selector changed");
            require(ApolloContractDriver.StatusFixture.commands == 0, "Apollo sent an entitlement command");
        }
        for (String rejected : expected.keySet()) {
            FixtureHookBackend failing = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
            failing.accept = method -> !method.getName().equals(rejected);
            try (HookRegistry hooks = new HookRegistry(failing);
                 ModuleRuntime runtime = runtime(failing, hooks)) {
                runtime.apply("code", 1, List.of("apollo"), optIn);
                require(runtime.snapshot().phase == ModuleRuntime.Phase.FAILED && failing.installed.isEmpty(),
                        "Partial Apollo installation did not roll back: " + rejected);
            }
        }
        FixtureHookBackend expired = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        try (HookRegistry hooks = new HookRegistry(expired);
             ModuleRuntime runtime = runtime(expired, hooks)) {
            runtime.apply("code", 1, List.of("apollo"), Map.of("apollo.enabled", "1", "apollo.bootId", "old-boot"));
            require(runtime.snapshot().phase == ModuleRuntime.Phase.FAILED && expired.installed.isEmpty(),
                    "Previous boot opt-in changed target methods");
        }
    }

    private static ModuleRuntime runtime(FixtureHookBackend backend, HookRegistry hooks) {
        return new ModuleRuntime("code", new ManualScheduler(), id -> new ApolloModule(ApolloContractDriver.methods(null), hooks, "current-boot"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
