package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.PhoneNumberPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class PhoneModule implements ModuleRuntime.Module {
    private static final String UTIL = "com.qinggan.bluetoothphone.util.Util";
    private static final String HEADSET = "com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private Method amend;
    private Method[] dials;

    public PhoneModule(MethodAccess methods, HookRegistry hooks) {
        this.methods = methods;
        this.hooks = hooks;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        amend = methods.require(UTIL, "getAmendNumber", "java.lang.String");
        Method single = methods.optional(HEADSET, "makeCall", "java.lang.String");
        Method device = methods.optional(HEADSET, "makeCall", "java.lang.String", "java.lang.String");
        if (single == null && device == null) {
            throw new NoSuchMethodException("No supported makeCall overload");
        }
        dials = new Method[] {single, device};
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (amend == null || dials == null) {
            throw new IllegalStateException("Phone module capabilities were not prepared");
        }
        scope.own(hooks.install("phone", amend, scope.callback(
                invocation -> PhoneNumberPolicy.normalize((String) invocation.arguments()[0]))));
        for (Method dial : dials) {
            if (dial != null) {
                scope.own(hooks.install("phone", dial, scope.callback(invocation -> {
                    invocation.arguments()[0] = PhoneNumberPolicy.normalize((String) invocation.arguments()[0]);
                    return invocation.proceed();
                })));
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
