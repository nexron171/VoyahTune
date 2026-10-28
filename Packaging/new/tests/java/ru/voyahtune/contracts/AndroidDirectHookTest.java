package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.HookRegistryTest;

public final class AndroidDirectHookTest {
    public static void main(String[] arguments) throws Throwable {
        NativeTestBootstrap.load(arguments[0]);
        HookRegistryTest.verify(true);
        verifyServiceCalls();
        verifyConstructor();
        System.out.println("AndroidDirectHookTest: lifecycle, dependencies, early JIT and constructor passed");
    }

    private static void verifyServiceCalls() throws Throwable {
        RadioService service = new RadioService();
        Player player = new Player(service);
        Method target = Player.class.getDeclaredMethod("playStation", String.class);
        int[] callbacks = {0};
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend())) {
            try (AutoCloseable handle = hooks.install("radio", target, invocation -> {
                     require(invocation.receiver() == player, "Player identity changed");
                     callbacks[0]++;
                     invocation.arguments()[0] = "redirected";
                     Object result = invocation.proceed();
                     require(result.equals(invocation.proceed()), "Original result changed");
                     return result;
                 })) {
                for (int index = 0; index < 10_000; index++) {
                    require("redirected".equals(player.onPlayButton()), "Caller bypassed early hook");
                }
                Thread.sleep(500);
                for (int index = 0; index < 1000; index++) {
                    require("redirected".equals(player.onPlayButton()), "Compiled caller bypassed early hook");
                }
                require(callbacks[0] == 11_000, "Callbacks were skipped or repeated");
                require(service.calls == 11_000, "Original side effects were skipped or repeated");
                require("redirected".equals(service.station), "Service dependency lost its state");
            }
            require("requested".equals(player.onPlayButton()), "Unhook did not restore the original");
            require(service.calls == 11_001, "Restored original did not use the service");
        }
    }

    private static void verifyConstructor() throws Throwable {
        RadioService original = new RadioService();
        RadioService replacement = new RadioService();
        try (HookRegistry hooks = new HookRegistry(HookBridge.backend())) {
            try (AutoCloseable handle = hooks.install(
                     "constructor", Player.class.getDeclaredConstructor(RadioService.class), invocation -> {
                         invocation.arguments()[0] = replacement;
                         return invocation.proceed();
                     })) {
                new Player(original).onPlayButton();
                require(original.calls == 0 && replacement.calls == 1, "Constructor substitution failed");
            }
            new Player(original).onPlayButton();
            require(original.calls == 1 && replacement.calls == 1, "Constructor unhook failed");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class RadioService {
        int calls;
        String station;

        String tune(String requested) {
            calls++;
            station = requested;
            return station;
        }
    }

    public static final class Player {
        private final RadioService service;

        public Player(RadioService service) {
            this.service = service;
        }

        public String playStation(String station) {
            return service.tune(station);
        }

        public String onPlayButton() {
            return playStation("requested");
        }
    }
}
