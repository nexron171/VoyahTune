package ru.voyahtune.agent;

import android.util.Log;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

public final class HookBridge {
    private HookBridge() {
    }

    public static HookRegistry.Backend backend() {
        return backend(null);
    }

    public static HookRegistry.Backend backend(ru.voyahtune.agent.runtime.AndroidCallers callers) {
        return new HookRegistry.Backend() {
            @Override
            public void prepare(Executable target) throws Exception {
                if (callers != null) {
                    String name = target.getDeclaringClass().getName() + "." + target.getName();
                    long started = System.nanoTime();
                    Log.i("VoyahTuneAgent", "Preparing callers: " + name);
                    callers.prepare(target);
                    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                    Log.i("VoyahTuneAgent", "Callers prepared: " + name + " in " + elapsedMillis + " ms");
                }
            }
            @Override
            public void installed(Executable target) {
                if (callers != null) {
                    callers.installed(target);
                }
                Log.i("VoyahTuneAgent", "Hook installed: " + target.getDeclaringClass().getName() + "."
                    + target.getName());
            }
            @Override
            public Method hook(Executable target, HookRegistry.Dispatch callback, Method callbackMethod) {
                return HookBridge.hook(target, callback, callbackMethod);
            }

            @Override
            public boolean unhook(Executable target) {
                return HookBridge.unhook(target);
            }
        };
    }

    public static native Method hook(Executable target, Object callback, Method callbackMethod);

    public static native boolean unhook(Executable target);

    public static native boolean isHooked(Executable target);

    public static native boolean deoptimize(Executable caller);

    public static native boolean deoptimizeClassInitializer(Class<?> owner);

    public static native Object[][] declaredMembersBatch(Class<?>[] owners, int kind);

    public static native int dexMethodIndex(Executable method);

    public static native Object[] declaredMembers(Class<?> owner, int kind);
}
