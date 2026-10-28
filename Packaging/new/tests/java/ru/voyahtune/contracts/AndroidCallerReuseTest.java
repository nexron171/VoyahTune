package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.AndroidCallers;

public final class AndroidCallerReuseTest {
    public static final class Calls {
        public static int first(int value) {
            return value + 1;
        }

        public static int second(int value) {
            return value + 2;
        }

        public static int both(int value) {
            return first(value) + second(value);
        }

        public static int onlySecond(int value) {
            return second(value);
        }

        public static int cycleFirst(int value) {
            return value == 0 ? 0 : cycleSecond(value - 1) + 1;
        }

        public static int cycleSecond(int value) {
            return value == 0 ? 0 : cycleFirst(value - 1) + 1;
        }

        public static int construct(int value) {
            return new Created(value).value;
        }
    }

    public static final class Created {
        final int value;

        public Created(int value) {
            this.value = Calls.first(value);
        }
    }

    public static void main(String[] arguments) throws Throwable {
        try {
            verify(arguments);
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void verify(String[] arguments) throws Throwable {
        NativeTestBootstrap.load(arguments[0]);
        for (int iteration = 0; iteration < 100_000; iteration++) {
            require(9, Calls.both(3));
            require(5, Calls.onlySecond(3));
            require(4, Calls.cycleFirst(4));
            require(4, Calls.construct(3));
        }
        Thread.sleep(500);
        boolean direct = arguments.length > 1 && arguments[1].equals("direct");
        AndroidCallers callers = direct ? null : new AndroidCallers(AndroidCallerReuseTest.class.getClassLoader());
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend(callers))) {
            Method both = Calls.class.getDeclaredMethod("both", int.class);
            Method onlySecond = Calls.class.getDeclaredMethod("onlySecond", int.class);
            Method first = Calls.class.getDeclaredMethod("first", int.class);
            Method second = Calls.class.getDeclaredMethod("second", int.class);
            try (AutoCloseable firstHook =
                     hooks.install("reuse-first", first, call -> (Integer) call.proceed() + 100)) {
                require(109, (Integer) both.invoke(null, 3));
                try (AutoCloseable secondHook =
                         hooks.install("reuse-second", second, call -> (Integer) call.proceed() + 1000)) {
                    require(1109, (Integer) both.invoke(null, 3));
                    require(1005, (Integer) onlySecond.invoke(null, 3));
                }
                require(109, (Integer) both.invoke(null, 3));
                require(5, (Integer) onlySecond.invoke(null, 3));
            }
            if (callers != null) {
                callers.releaseIndex();
            }
            try (AutoCloseable hook =
                     hooks.install("rebuilt-second", second, call -> (Integer) call.proceed() + 10)) {
                require(19, (Integer) both.invoke(null, 3));
                require(15, (Integer) onlySecond.invoke(null, 3));
            }
            require(9, (Integer) both.invoke(null, 3));
            Method cycle = Calls.class.getDeclaredMethod("cycleFirst", int.class);
            Method nested = Calls.class.getDeclaredMethod("cycleSecond", int.class);
            try (AutoCloseable outerHook =
                     hooks.install("cycle-first", cycle, call -> (Integer) call.proceed() + 10)) {
                require(34, (Integer) cycle.invoke(null, 4));
                try (AutoCloseable innerHook =
                         hooks.install("cycle-second", nested, call -> (Integer) call.proceed() + 100)) {
                    require(234, (Integer) cycle.invoke(null, 4));
                }
            }
            Method factory = Calls.class.getDeclaredMethod("construct", int.class);
            try (AutoCloseable constructorHook =
                     hooks.install("constructor", Created.class.getDeclaredConstructor(int.class), call -> {
                         call.arguments()[0] = (Integer) call.arguments()[0] + 2;
                         return call.proceed();
                     })) {
                require(6, (Integer) factory.invoke(null, 3));
            }
            require(4, (Integer) factory.invoke(null, 3));
        }
        System.out.println("AndroidCallerReuseTest: overlapping and independent compiled callers passed");
    }

    private static void require(int expected, int actual) {
        if (expected != actual)
            throw new AssertionError("Expected " + expected + ", received " + actual);
    }
}
