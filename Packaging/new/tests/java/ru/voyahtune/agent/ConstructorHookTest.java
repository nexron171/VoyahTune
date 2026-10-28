package ru.voyahtune.agent;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

public final class ConstructorHookTest {
    public static void verify() throws Exception {
        Constructor<Fixture> target = Fixture.class.getDeclaredConstructor(int.class);
        List<Fixture> captured = new ArrayList<>();
        int before = Fixture.calls;
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend())) {
            AutoCloseable registration = hooks.install("constructor-test", target, invocation -> {
                invocation.arguments()[0] = (Integer) invocation.arguments()[0] + 2;
                Object result = invocation.proceed();
                Fixture receiver = (Fixture) invocation.receiver();
                require(receiver.value == (Integer) invocation.arguments()[0], "Callback observed an uninitialized receiver");
                captured.add(receiver);
                return result;
            });
            AutoCloseable failingCapture = hooks.install("constructor-failing-capture", target, invocation -> {
                invocation.proceed();
                throw new IllegalStateException("capture-failed-after-original");
            });
            Fixture first = new Fixture(5);
            require(first.value == 7 && Fixture.calls == before + 1 && captured.size() == 1 && captured.get(0) == first,
                    "Constructor did not initialize the original receiver exactly once");
            try {
                new Fixture(-3);
                throw new AssertionError("Original constructor exception was lost");
            } catch (IllegalStateException failure) {
                require(failure == Fixture.failure && Fixture.calls == before + 2, "Constructor exception identity or call count changed");
            }
            registration.close();
            failingCapture.close();
            require(!HookBridge.isHooked(target), "Constructor remained hooked after cleanup");
            Fixture restored = new Fixture(5);
            require(restored.value == 5 && Fixture.calls == before + 3 && captured.size() == 1, "Original constructor was not restored");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static final class Fixture {
        static int calls;
        static final IllegalStateException failure = new IllegalStateException("constructor-original-failure");
        final int value;

        public Fixture(int value) {
            calls++;
            if (value < 0) throw failure;
            this.value = value;
        }
    }
}
