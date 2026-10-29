package ru.voyahtune.agent;

import java.util.concurrent.atomic.AtomicInteger;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ModuleListenerTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("ModuleListenerTest: passed");
    }

    public static void verify() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ModuleScope scope = new ModuleScope((delay, task) -> () -> {}, 1);
        Runnable listener = scope.listener(effects::incrementAndGet);
        listener.run();
        require(effects.get() == 0, "Preparing listener performed work");
        scope.configure(1, () -> {});
        Runnable oldTask = scope.guard(() -> effects.addAndGet(100));
        listener.run();
        scope.configure(2, () -> {});
        oldTask.run();
        listener.run();
        require(effects.get() == 2, "Listener did not survive configuration revision or a stale task ran");
        scope.close();
        listener.run();
        require(effects.get() == 2, "Disabled listener performed work");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
