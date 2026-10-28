package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.KeyboardPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class KeyboardInputModeModule implements ModuleRuntime.Module {
    private final String owner;
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private Method save;

    public KeyboardInputModeModule(String owner, MethodAccess methods, HookRegistry hooks) {
        this.owner = owner;
        this.methods = methods;
        this.hooks = hooks;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        save = methods.require("com.qinggan.app.qgime.InputModeSwitcher", "saveInputMode", "int");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(hooks.install(owner, save, scope.callback(invocation -> {
            invocation.arguments()[0] = KeyboardPolicy.englishMode((Integer) invocation.arguments()[0]);
            return invocation.proceed();
        })));
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
