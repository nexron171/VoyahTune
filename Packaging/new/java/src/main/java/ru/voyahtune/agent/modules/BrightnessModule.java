package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

/** HiBoard's user setters exclude feedback updates and Native's background writes. */
public final class BrightnessModule implements ModuleRuntime.Module {
    public interface Delivery {
        void send(int screen, int percent) throws Exception;
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Delivery delivery;
    private final Consumer<Exception> diagnostics;
    private final AtomicLong mainRevision = new AtomicLong();
    private final AtomicLong passengerRevision = new AtomicLong();
    private Method mainSetter;
    private Method passengerSetter;

    public BrightnessModule(MethodAccess methods, HookRegistry hooks, Delivery delivery,
            Consumer<Exception> diagnostics) {
        this.methods = methods;
        this.hooks = hooks;
        this.delivery = delivery;
        this.diagnostics = diagnostics;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        String owner = "com.qinggan.app.base.manager.BackLightManagerWrapper";
        mainSetter = methods.require(owner, "setBackLightImpl", "int");
        passengerSetter = methods.require(owner, "setSecondScreenBackLightImpl", "int");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        installSetter(scope, mainSetter, 1, mainRevision);
        installSetter(scope, passengerSetter, 2, passengerRevision);
    }

    private void installSetter(ModuleScope scope, Method setter, int screen, AtomicLong revision)
            throws Exception {
        scope.own(hooks.install("brightness", setter, scope.callback(invocation -> {
            Object result = invocation.proceed();
            int percent = ((Number) invocation.arguments()[0]).intValue();
            if (percent >= 1 && percent <= 100) {
                long current = revision.incrementAndGet();
                try {
                    scope.schedule(150, () -> {
                        if (revision.get() != current) return;
                        try {
                            delivery.send(screen, percent);
                        } catch (Exception failure) {
                            diagnostics.accept(failure);
                        }
                    });
                } catch (Exception failure) {
                    diagnostics.accept(failure);
                }
            }
            return result;
        })));
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
