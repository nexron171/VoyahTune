package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ApolloModule implements ModuleRuntime.Module {
    private static final String ROOT = "com.qinggan.app.vehiclesetting.fragments.driveassistance.";
    private static final String SUBSCRIPTION = "{\"expireStatus\":\"0\",\"isMqtt\":false,\"remainDays\":\"30\",\"subscriptionStatus\":\"1\"}";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final String bootId;
    private Map<Method, Object> getters;

    public ApolloModule(MethodAccess methods, HookRegistry hooks, String bootId) {
        this.methods = methods;
        this.hooks = hooks;
        this.bootId = bootId;
    }

    public static boolean allowed(Map<String, String> configuration, String bootId) {
        return configuration.getOrDefault("apollo.enabled", "0").equals("1")
                && bootId.equals(configuration.get("apollo.bootId"));
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        if (!allowed(configuration, bootId)) {
            throw new IllegalArgumentException("Apollo requires explicit opt-in from this boot");
        }
        Map<Method, Object> resolved = new LinkedHashMap<>();
        resolve(resolved, "adas.BaiduProviderUtil", "doQuerySubscribeInfo", SUBSCRIPTION, "android.content.Context");
        resolve(resolved, "adas.BaiduProviderUtil", "doQueryNOALearnInfo", "1", "android.content.Context", "java.lang.String");
        resolve(resolved, "DriveAssistantConfig", "isSupportSDB", true);
        resolve(resolved, "DriveAssistantData", "getStatusType", 1002);
        resolve(resolved, "DriveAssistantData", "isShowAIIntelligence", true);
        resolve(resolved, "adas.DriveAssistanceAdasStatusManager", "getSubscriptionStatus", true);
        resolve(resolved, "adas.DriveAssistanceAdasStatusManager", "getExpireStatus", true);
        resolve(resolved, "adas.DriveAssistanceAdasStatusManager", "getRemainDay", 30);
        resolve(resolved, "adas.DriveAssistanceAdasStatusManager", "getLearnStatus", 1);
        getters = resolved;
    }

    private void resolve(Map<Method, Object> resolved, String owner, String name, Object value,
                         String... parameters) throws Exception {
        Method method = methods.require(ROOT + owner, name, parameters);
        Class<?> type = method.getReturnType();
        boolean compatible = type == boolean.class && value instanceof Boolean
                || type == int.class && value instanceof Integer || type == String.class && value instanceof String;
        if (!compatible) {
            throw new NoSuchMethodException("Unsupported Apollo getter result: " + owner + "." + name);
        }
        resolved.put(method, value);
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (Map.Entry<Method, Object> getter : getters.entrySet()) {
            scope.own(hooks.install("apollo", getter.getKey(), scope.callback(invocation -> getter.getValue())));
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {
        if (!allowed(configuration, bootId)) {
            throw new IllegalArgumentException("Apollo opt-in expired");
        }
    }
}
