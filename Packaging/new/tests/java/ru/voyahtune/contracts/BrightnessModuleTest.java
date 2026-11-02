package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.BrightnessModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class BrightnessModuleTest {
    public static void main(String[] arguments) throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(false);
        ManualScheduler scheduler = new ManualScheduler();
        List<String> deliveries = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        MethodAccess methods = new MethodAccess(name -> Wrapper.class);
        ModuleRuntime runtime = new ModuleRuntime("code", scheduler, id -> new BrightnessModule(
                methods, new HookRegistry(backend),
                (screen, percent) -> deliveries.add(screen + "=" + percent), failures::add));
        runtime.apply("code", 1, List.of("brightness"), Map.of());
        require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Module did not become ready");
        Wrapper wrapper = new Wrapper();
        Method main = Wrapper.class.getDeclaredMethod("setBackLightImpl", int.class);
        Method passenger = Wrapper.class.getDeclaredMethod("setSecondScreenBackLightImpl", int.class);
        backend.invoke(main, wrapper, 35);
        backend.invoke(main, wrapper, 47);
        backend.invoke(passenger, wrapper, 62);
        require(wrapper.writes == 3 && deliveries.isEmpty(), "Changed OEM behavior or delivered synchronously");
        scheduler.advance(150);
        require(deliveries.equals(List.of("1=47", "2=62")), "Latest manual values were not delivered per screen");
        backend.invoke(main, wrapper, 0);
        scheduler.advance(150);
        require(deliveries.size() == 2, "Invalid brightness was persisted");
        wrapper.fail = true;
        try {
            backend.invoke(main, wrapper, 50);
            throw new AssertionError("OEM failure was swallowed");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().equals("OEM unavailable"), "OEM failure changed");
        }
        scheduler.advance(150);
        require(deliveries.size() == 2, "Failed OEM send was persisted");
        wrapper.fail = false;
        backend.invoke(main, wrapper, 55);
        runtime.close();
        scheduler.advance(150);
        require(deliveries.size() == 2 && failures.isEmpty(), "Closed module delivered stale calibration");
        System.out.println("BrightnessModuleTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Wrapper {
        int writes;
        boolean fail;

        public void setBackLightImpl(int percent) {
            if (fail) throw new IllegalStateException("OEM unavailable");
            writes++;
        }

        public void setSecondScreenBackLightImpl(int percent) {
            setBackLightImpl(percent);
        }
    }
}
