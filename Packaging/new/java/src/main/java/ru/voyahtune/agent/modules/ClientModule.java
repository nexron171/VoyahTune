package ru.voyahtune.agent.modules;

import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ClientModule implements ModuleRuntime.Module {
    private final List<ModuleRuntime.Module> modules;

    public ClientModule(ClientGeometryModule geometry, ClientInputMethodModule inputMethod) {
        modules = List.of(geometry, inputMethod);
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        for (ModuleRuntime.Module module : modules) {
            module.prepare(configuration);
        }
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (ModuleRuntime.Module module : modules) {
            module.install(scope, configuration);
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (ModuleRuntime.Module module : modules) {
            module.configure(scope, configuration);
        }
    }

    @Override
    public boolean ready() {
        return modules.stream().allMatch(ModuleRuntime.Module::ready);
    }
}
