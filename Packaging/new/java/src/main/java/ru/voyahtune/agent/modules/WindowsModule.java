package ru.voyahtune.agent.modules;

import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class WindowsModule implements ModuleRuntime.Module {
    public interface Preparation {
        long now();
        void probe() throws Exception;
        void pause(long millis) throws InterruptedException;
    }

    private final List<ModuleRuntime.Module> modules;
    private final Preparation platform;

    public WindowsModule(Preparation platform, WindowPrivilegesModule privileges, WindowGeometryModule geometry,
                         WindowMemoryProtectionModule memory, WindowWifiModule wifi) {
        this.platform = platform;
        modules = List.of(privileges, geometry, memory, wifi);
    }

    public void preflight(Map<String, String> configuration) throws Exception {
        platform.probe();
        for (ModuleRuntime.Module module : modules) module.prepare(configuration);
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        WindowPreparation preparation = new WindowPreparation(platform::now, () -> preflight(configuration));
        while (preparation.poll() == WindowPreparation.State.PREPARING) platform.pause(500);
        if (preparation.poll() == WindowPreparation.State.FAILED) throw preparation.failure();
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (ModuleRuntime.Module module : modules) module.install(scope, configuration);
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (ModuleRuntime.Module module : modules) module.configure(scope, configuration);
    }

    @Override public boolean ready() { return modules.stream().allMatch(ModuleRuntime.Module::ready); }
}
