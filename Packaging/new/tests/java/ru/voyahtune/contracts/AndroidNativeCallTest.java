package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.AndroidCallers;

public final class AndroidNativeCallTest {
    public static void main(String[] arguments) throws Throwable {
        NativeTestBootstrap.load(arguments[0]);
        verify();
        System.out.println("AndroidNativeCallTest: warmed JNI calls, original dispatch and unhook passed");
    }

    static void verify() throws Throwable {
        for (int index = 0; index < 100_000; index++) {
            if (call(index) != index + 10)
                throw new AssertionError("Native warmup result changed");
        }
        Thread.sleep(500);
        Method target = AndroidNativeCallTest.class.getDeclaredMethod("value", int.class);
        HookRegistry hooks =
            new HookRegistry(HookBridge.backend(new AndroidCallers(AndroidNativeCallTest.class.getClassLoader())));
        try (AutoCloseable hook =
                 hooks.install("native-probe", target, invocation -> (Integer) invocation.proceed() + 100)) {
            for (int index = 0; index < 1000; index++) {
                if (call(index) != index + 110)
                    throw new AssertionError("Warmed native caller bypassed hook");
            }
        }
        hooks.close();
        for (int index = 0; index < 1000; index++) {
            if (call(index) != index + 10)
                throw new AssertionError("Native unhook changed original result");
        }
    }

    private static int call(int value) {
        return value(value);
    }

    private static native int value(int input);
}
