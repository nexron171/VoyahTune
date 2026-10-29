package ru.voyahtune.contracts;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.AndroidCallers;

public final class AndroidCallerMissingTypeTest {
    public static final class MissingDependency {}

    public static final class Callers {
        public static String overloaded(int value) {
            return leaf();
        }

        public static String overloaded(MissingDependency value) {
            return leaf();
        }

        public static String leaf() {
            return "original";
        }
    }

    public static void main(String[] arguments) {
        try {
            verify(arguments);
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void verify(String[] arguments) throws Exception {
        NativeTestBootstrap.load(arguments[0]);
        try {
            Class.forName(AndroidCallerMissingTypeTest.class.getName() + "$MissingDependency");
            throw new AssertionError("Exclude MissingDependency from the test DEX");
        } catch (ClassNotFoundException expected) {
        }
        for (int index = 0; index < 100_000; index++) {
            require("original", Callers.overloaded(index));
        }
        AndroidCallers callers = new AndroidCallers(AndroidCallerMissingTypeTest.class.getClassLoader());
        HookRegistry hooks = new HookRegistry(new HookRegistry.Backend() {
            @Override
            public void prepare(Executable target) throws Exception {
                callers.prepare(target);
            }

            @Override
            public void installed(Executable target) {
                callers.installed(target);
            }

            @Override
            public Method hook(Executable target, HookRegistry.Dispatch dispatch, Method callback) {
                return HookBridge.hook(target, dispatch, callback);
            }

            @Override
            public boolean unhook(Executable target) {
                return HookBridge.unhook(target);
            }
        });
        Method target = Callers.class.getDeclaredMethod("leaf");
        Method caller = Callers.class.getDeclaredMethod("overloaded", int.class);
        try (AutoCloseable registration = hooks.install("missing-type", target, invocation -> "hooked")) {
            callers.releaseIndex();
            require("hooked", (String) caller.invoke(null, 1));
        }
        require("original", (String) caller.invoke(null, 1));
        hooks.close();
        System.out.println("AndroidCallerMissingTypeTest: unresolved overload signatures passed");
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", received " + actual);
        }
    }
}
