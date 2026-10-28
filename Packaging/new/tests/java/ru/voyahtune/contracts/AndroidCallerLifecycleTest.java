package ru.voyahtune.contracts;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.AndroidCallers;

public final class AndroidCallerLifecycleTest {
    public static void main(String[] arguments) throws Throwable {
        NativeTestBootstrap.load(arguments[0]);
        verify();
        System.out.println("AndroidCallerLifecycleTest: index release, pending coverage and rebuilding passed");
    }

    static void verify() throws Throwable {
        for (int index = 0; index < 100_000; index++) {
            require("warm!", DexCallerFixtures.outer("warm"));
        }
        Thread.sleep(500);
        AndroidCallers callers = new AndroidCallers(AndroidCallerLifecycleTest.class.getClassLoader());
        HookRegistry hooks = new HookRegistry(new HookRegistry.Backend() {
            @Override
            public void prepare(Executable target) throws Exception {
                callers.prepare(target);
                callers.releaseIndex();
            }

            @Override
            public void installed(Executable target) {
                callers.installed(target);
                callers.releaseIndex();
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
        try {
            for (String name : new String[] {"leaf", "middle"}) {
                Method target = DexCallerFixtures.class.getDeclaredMethod(name, String.class);
                try (AutoCloseable hook =
                         hooks.install("caller-lifecycle", target, invocation -> invocation.proceed() + "hook")) {
                    for (int index = 0; index < 1000; index++) {
                        require("check!hook", DexCallerFixtures.outer("check"));
                    }
                }
                require("original!", DexCallerFixtures.outer("original"));
            }
        } finally {
            hooks.close();
        }
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", received " + actual);
        }
    }
}
