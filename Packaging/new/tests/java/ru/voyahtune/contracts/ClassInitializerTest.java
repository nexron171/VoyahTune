package ru.voyahtune.contracts;

import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;

public final class ClassInitializerTest {
    private static int initializations;

    public static final class Value {
        public static int next() { initializations++; return 7; }
    }
    public static final class LazyClass {
        public static final int VALUE = Value.next();
    }
    public interface LazyInterface {
        int VALUE = Value.next();
    }
    public static final class NoInitializer {}

    public static void verify() throws Exception {
        if (!HookBridge.deoptimizeClassInitializer(NoInitializer.class)) throw new AssertionError("Initializer API unavailable");
        ClassLoader loader = ClassInitializerTest.class.getClassLoader();
        Class<?> type = Class.forName(ClassInitializerTest.class.getName() + "$LazyClass", false, loader);
        Class<?> contract = Class.forName(ClassInitializerTest.class.getName() + "$LazyInterface", false, loader);
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend())) {
            hooks.install("initializer-test", Value.class.getDeclaredMethod("next"), invocation -> 42);
            if (!HookBridge.deoptimizeClassInitializer(type) || !HookBridge.deoptimizeClassInitializer(contract)
                    || !HookBridge.deoptimizeClassInitializer(NoInitializer.class)) throw new AssertionError("Initializer invalidation failed");
            if (initializations != 0) throw new AssertionError("Invalidation ran a static initializer");
            if (LazyClass.VALUE != 42 || LazyInterface.VALUE != 42) throw new AssertionError("Initializer bypassed hooked callee");
            if (initializations != 0) throw new AssertionError("Original was called despite replacement");
        }
    }

    public static void main(String[] arguments) throws Exception {
        try {
            NativeTestBootstrap.load(arguments[0]);
            verify();
            System.out.println("ClassInitializerTest: passed");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
