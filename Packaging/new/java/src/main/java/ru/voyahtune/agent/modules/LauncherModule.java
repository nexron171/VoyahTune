package ru.voyahtune.agent.modules;

import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class LauncherModule implements ModuleRuntime.Module {
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final LauncherPlatform platform;
    private final LauncherDockModule dock;
    private LauncherAllAppsModule applications;

    public LauncherModule(MethodAccess methods, HookRegistry hooks, LauncherPlatform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
        dock = new LauncherDockModule(methods, hooks, platform);
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        dock.prepare(configuration);
        if (applications == null) applications = new LauncherAllAppsModule(methods, hooks, platform, dock.shared());
        applications.prepare(configuration);
    }
    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        dock.install(scope, configuration);
        applications.install(scope, configuration);
    }
    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        dock.configure(scope, configuration);
        applications.configure(scope, configuration);
    }
    @Override public boolean ready() { return dock.ready() && applications != null && applications.ready(); }
}
