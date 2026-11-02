package ru.voyahtune.agent.modules;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Observes available OEM 0x396 decoders without opening another CAN reader. */
public final class CabinClimateModule implements ModuleRuntime.Module {
    public static final class Sample {
        public final int raw;
        public final long sampledAt;

        Sample(int raw, long sampledAt) {
            this.raw = raw;
            this.sampledAt = sampledAt;
        }
    }

    public interface Delivery {
        void publish(Sample sample) throws Exception;

        default void register(ModuleScope scope, Supplier<Sample> latest) throws Exception {}

        void error(Exception failure);
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final LongSupplier clock;
    private final Delivery delivery;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Sample latest;
    private final Set<Method> parsers = new LinkedHashSet<>();

    public CabinClimateModule(
            MethodAccess methods, HookRegistry hooks, LongSupplier clock, Delivery delivery) {
        this.methods = methods;
        this.hooks = hooks;
        this.clock = clock;
        this.delivery = delivery;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        parsers.clear();
        for (String owner :
                new String[] {
                    "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl",
                    "com.qinggan.canbus.service.protocol.dongfeng.DongfengCanBusComponentImpl",
                    "com.qinggan.canbus.service.protocol.dongfeng_h56.DongfengH56CanBusComponentImpl",
                    "com.qinggan.canbus.service.protocol.dongfeng_h56b.DongfengH56BCanBusComponentImpl"
                }) {
            try {
                Method parser = methods.optional(owner, "onACControlChangeData", "[I", "boolean");
                if (parser != null) parsers.add(parser);
            } catch (ClassNotFoundException unsupported) {
                // SDK variants omit protocol classes that are not shipped for the vehicle.
            }
        }
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (parsers.isEmpty()) return;
        delivery.register(scope, () -> latest);
        for (Method parser : parsers) {
            scope.own(
                    hooks.install(
                            "cabin-climate",
                            parser,
                            scope.callback(
                                    invocation -> {
                                        Object result = invocation.proceed();
                                        int[] frame = (int[]) invocation.arguments()[0];
                                        if (frame != null
                                                && frame.length == 8
                                                && frame[1] >= 0
                                                && frame[1] < 255) {
                                            latest = new Sample(frame[1], clock.getAsLong());
                                            if (started.compareAndSet(false, true)) {
                                                try {
                                                    scope.schedule(0, () -> publish(scope));
                                                } catch (Exception failure) {
                                                    started.set(false);
                                                    delivery.error(failure);
                                                }
                                            }
                                        }
                                        return result;
                                    })));
        }
        scope.own(
                () -> {
                    latest = null;
                    started.set(false);
                });
    }

    private void publish(ModuleScope scope) {
        try {
            Sample sample = latest;
            if (sample != null) delivery.publish(sample);
        } catch (Exception failure) {
            delivery.error(failure);
        } finally {
            try {
                scope.schedule(60_000, () -> publish(scope));
            } catch (Exception failure) {
                if (!scope.stoppingOrFailed()) delivery.error(failure);
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        // A new runtime revision cancels the previous scope timers.
        if (latest != null) scope.schedule(0, () -> publish(scope));
    }
}
