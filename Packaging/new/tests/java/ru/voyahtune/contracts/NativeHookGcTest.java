package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;

public final class NativeHookGcTest {
    public static final class Target {
        public static Object value(Object input) {
            return input;
        }
        public Object wide(Object first, long number, double decimal, Object second) {
            if (number != 1234567890123L || decimal != 4.25 || first == second) {
                throw new AssertionError("Arguments changed");
            }
            return second;
        }
    }

    public static void verify() throws Exception {
        Method method = Target.class.getDeclaredMethod("value", Object.class);
        Method wide = Target.class.getDeclaredMethod("wide", Object.class, long.class, double.class, Object.class);
        Target receiver = new Target();
        Object stock = new Object();
        Object replacement = new Object();
        AtomicBoolean collecting = new AtomicBoolean(true);
        Thread collector = new Thread(() -> {
            byte[][] retained = new byte[64][];
            int index = 0;
            while (collecting.get()) {
                retained[index++ % retained.length] = new byte[128 * 1024];
                Thread.yield();
            }
        }, "VoyahTuneGcTest");
        collector.start();
        try {
            for (int iteration = 0; iteration < 600; iteration++) {
                try (HookRegistry registry = new HookRegistry(HookBridge.backend())) {
                    registry.install("gc", method, invocation -> replacement);
                    registry.install("wide", wide, invocation -> invocation.proceed());
                    if (method.invoke(null, stock) != replacement)
                        throw new AssertionError("Hook lost during GC");
                    if (wide.invoke(receiver, stock, 1234567890123L, 4.25, replacement) != replacement) {
                        throw new AssertionError("Wide call changed during GC");
                    }
                }
                if (method.invoke(null, stock) != stock)
                    throw new AssertionError("Unhook did not restore original");
            }
        } finally {
            collecting.set(false);
            collector.join(5000);
            if (collector.isAlive())
                throw new AssertionError("Collector did not stop");
        }
        System.gc();
    }

    public static void main(String[] arguments) throws Exception {
        NativeTestBootstrap.load(arguments[0]);
        verify();
        System.out.println("NativeHookGcTest: passed");
    }
}
