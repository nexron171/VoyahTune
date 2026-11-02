package ru.voyahtune.contracts;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.CabinClimateModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class CabinClimateModuleTest {
    public static void main(String[] arguments) throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(false);
        ManualScheduler scheduler = new ManualScheduler();
        List<Integer> sent = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        List<Supplier<CabinClimateModule.Sample>> caches = new ArrayList<>();
        CabinClimateModule.Delivery delivery =
                new CabinClimateModule.Delivery() {
                    public void publish(CabinClimateModule.Sample sample) {
                        sent.add(sample.raw);
                    }

                    public void error(Exception failure) {
                        failures.add(failure);
                    }

                    public void register(
                            ModuleScope scope, Supplier<CabinClimateModule.Sample> latest) {
                        caches.add(latest);
                    }
                };
        ModuleRuntime runtime =
                new ModuleRuntime(
                        "code",
                        scheduler,
                        id ->
                                new CabinClimateModule(
                                        new MethodAccess(
                                                name -> {
                                                    if (name.equals("[I")) return int[].class;
                                                    if (name.contains("dongfeng_h97c"))
                                                        return Decoder.class;
                                                    if (name.contains("dongfeng_h56b"))
                                                        return OtherDecoder.class;
                                                    throw new ClassNotFoundException(name);
                                                }),
                                        new HookRegistry(backend),
                                        scheduler::now,
                                        delivery));
        runtime.apply("code", 1, List.of("cabin-climate"), Map.of());
        require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Module not ready");
        Decoder decoder = new Decoder();
        Method parser =
                Decoder.class.getMethod("onACControlChangeData", int[].class, boolean.class);
        require(caches.get(0).get() == null, "Invented initial value");
        backend.invoke(parser, decoder, new int[] {117, 121, 124, 0, 120, 58, 114, 1}, true);
        require(
                decoder.calls == 1 && sent.isEmpty(),
                "Changed OEM processing or synchronous delivery");
        scheduler.advance(0);
        require(sent.equals(List.of(121)), "Initial frame was not delivered immediately");
        scheduler.advance(59_999);
        require(sent.size() == 1, "Published faster than once a minute");
        scheduler.advance(1);
        require(sent.equals(List.of(121, 121)), "Unchanged measurement not republished");
        backend.invoke(parser, decoder, new int[] {117, 124, 124, 0, 120, 58, 114, 1}, true);
        require(caches.get(0).get().raw == 124, "Restart cache not updated");
        backend.invoke(parser, decoder, new int[] {117, 255, 124, 0, 120, 58, 114, 1}, true);
        backend.invoke(parser, decoder, new int[] {117, 100}, true);
        scheduler.advance(60_000);
        require(sent.equals(List.of(121, 121, 124)), "Invalid frame erased last valid measurement");
        decoder.fail = true;
        try {
            backend.invoke(parser, decoder, new int[] {117, 126, 124, 0, 120, 58, 114, 1}, true);
            throw new AssertionError("OEM exception was swallowed");
        } catch (IllegalStateException expected) {
            require(caches.get(0).get().raw == 124, "Failed OEM processing changed the sample");
        }
        runtime.apply("code", 2, List.of("cabin-climate"), Map.of());
        scheduler.advance(0);
        require(sent.size() == 4, "Reconfiguration did not restore the observation timer");
        scheduler.advance(60_000);
        require(
                sent.size() == 5 && sent.get(4) == 124,
                "Reconfigured timer stopped or duplicated delivery");
        Method otherParser =
                OtherDecoder.class.getMethod("onACControlChangeData", int[].class, boolean.class);
        backend.invoke(
                otherParser,
                new OtherDecoder(),
                new int[] {117, 122, 124, 0, 120, 58, 114, 1},
                true);
        scheduler.advance(60_000);
        require(
                sent.size() == 6 && sent.get(5) == 122,
                "Another available OEM adapter was ignored");
        runtime.close();
        scheduler.advance(120_000);
        require(sent.size() == 6 && failures.isEmpty(), "Closed module still delivered or failed");
        System.out.println("CabinClimateModuleTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Decoder {
        int calls;
        boolean fail;

        public void onACControlChangeData(int[] data, boolean notify) {
            if (fail) throw new IllegalStateException("OEM failure");
            calls++;
        }
    }

    public static final class OtherDecoder {
        public void onACControlChangeData(int[] data, boolean notify) {}
    }
}
