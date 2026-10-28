package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ModuleHealthTest {
    public static void main(String[] arguments) throws Throwable { verify(); System.out.println("ModuleHealthTest: passed"); }

    static void verify() throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        HookRegistry hooks = new HookRegistry(backend);
        AtomicReference<ModuleScope> installed = new AtomicReference<>();
        int[] effects = {0};
        Method target = Fixture.class.getDeclaredMethod("value", int.class);
        ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new ModuleRuntime.Module() {
            @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
                installed.set(scope);
                scope.own(hooks.install(id, target, scope.callback(invocation -> {
                    if (invocation.arguments()[0].equals(-1)) {
                        scope.requireRestart(new IllegalStateException("Unconfirmed asynchronous window rollback"));
                    } else invocation.arguments()[0] = (Integer) invocation.arguments()[0] + 10;
                    return invocation.proceed();
                })));
            }
            @Override public void configure(ModuleScope scope, Map<String, String> configuration) {}
        });
        try {
            runtime.apply("code", 1, List.of("health"), Map.of());
            Fixture fixture = new Fixture();
            require(backend.invoke(target, fixture, 1).equals(12), "Active health fixture did not intercept original");
            require(backend.invoke(target, fixture, -1).equals(0), "Failed callback did not complete stock operation");
            require(runtime.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED && runtime.snapshot().failure != null,
                    "Asynchronous uncertainty still reported READY");
            installed.get().listener(() -> effects[0]++).run();
            require(effects[0] == 0 && backend.invoke(target, fixture, 1).equals(2), "Failed scope retained listener/hook effects");
            require(runtime.apply("code", 2, List.of("health"), Map.of()).phase == ModuleRuntime.Phase.RESTART_REQUIRED,
                    "Reconfiguration revived a failed scope");
            runtime.close();
            require(runtime.snapshot().phase == ModuleRuntime.Phase.CLOSED && backend.installed.isEmpty(), "Confirmed cleanup did not quiesce failed scope");
        } finally { runtime.close(); hooks.close(); }
    }

    static final class Fixture { int value(int value) { return value + 1; } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
