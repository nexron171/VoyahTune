package ru.voyahtune.contracts;

import ru.voyahtune.agent.HookBridge;

final class NativeTestBootstrap {
    private NativeTestBootstrap() {}

    static void load(String library) {
        System.load(library);
        if (!registerBridge(HookBridge.class)) {
            throw new IllegalStateException("Test HookBridge registration failed");
        }
    }

    private static native boolean registerBridge(Class<?> bridge);
}
