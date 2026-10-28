package ru.voyahtune.contracts;

import java.lang.reflect.Proxy;
import java.util.Map;
import dalvik.system.PathClassLoader;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.LauncherModule;
import ru.voyahtune.agent.modules.LauncherPlatform;
import ru.voyahtune.agent.runtime.MethodAccess;

public final class AndroidLauncherAbiTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !arguments[0].equals("--native-library")) throw new IllegalArgumentException("Native bridge and OEM classpath required");
        NativeTestBootstrap.load(arguments[1]);
        ClassLoader loader = new PathClassLoader(arguments[2], ClassLoader.getSystemClassLoader());
        MethodAccess methods = new MethodAccess(name -> Class.forName(name, false, loader));
        LauncherPlatform platform = (LauncherPlatform) Proxy.newProxyInstance(LauncherPlatform.class.getClassLoader(), new Class<?>[] {LauncherPlatform.class}, (receiver, method, values) -> {
            if (method.getName().equals("settings")) return Map.of();
            if (method.getName().equals("error")) throw new IllegalStateException("Optional launcher ABI unavailable", (Exception) values[0]);
            throw new AssertionError("Preflight attempted a launcher side effect: " + method.getName());
        });
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend())) {
            new LauncherModule(methods, hooks, platform).prepare(Map.of());
        }
        System.out.println("AndroidLauncherAbiTest: OEM navigation and All Apps ABI passed; no launcher hooks installed");
    }
}
