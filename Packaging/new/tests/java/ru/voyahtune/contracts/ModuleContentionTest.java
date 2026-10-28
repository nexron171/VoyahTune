package ru.voyahtune.contracts;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ModuleContentionTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("ModuleContentionTest: passed");
    }

    static void verify() throws Throwable {
        Object oemLock = new Object();
        CountDownLatch oemHeld = new CountDownLatch(1);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch invoke = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ModuleScope> owned = new AtomicReference<>();
        AtomicInteger effects = new AtomicInteger();
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        HookRegistry hooks = new HookRegistry(backend);
        Method target = Fixture.class.getDeclaredMethod("value", int.class);
        ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new ModuleRuntime.Module() {
            @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
                owned.set(scope);
                scope.own(hooks.install(id, target, scope.callback(call -> { effects.incrementAndGet(); return call.proceed(); })));
            }
            @Override public void configure(ModuleScope scope, Map<String, String> configuration) {}
        });
        runtime.apply("code", 1, List.of("contention"), Map.of());
        Thread oem = thread("OEM holds global lock", failure, () -> {
            synchronized (oemLock) {
                oemHeld.countDown();
                if (!invoke.await(2, TimeUnit.SECONDS)) throw new AssertionError("Writer was not queued");
                if (!backend.invoke(target, new Fixture(), 41).equals(42)) throw new AssertionError("Original result changed");
            }
        });
        oem.start();
        require(oemHeld.await(2, TimeUnit.SECONDS), "OEM did not enter lock");
        Thread worker = thread("Agent requests global lock", failure, () -> owned.get().listener(() -> {
            workerEntered.countDown();
            synchronized (oemLock) { /* The real WindowManager operation needs the OEM monitor. */ }
        }).run());
        worker.start();
        require(workerEntered.await(2, TimeUnit.SECONDS), "Agent did not enter scope");
        Thread writer = thread("New configuration", failure, () -> runtime.apply("code", 2, List.of("contention"), Map.of()));
        writer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (writer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        require(writer.getState() == Thread.State.WAITING, "Configuration did not wait for active work");
        invoke.countDown();
        for (Thread thread : new Thread[] {oem, worker, writer}) thread.join(2000);
        require(!oem.isAlive() && !worker.isAlive() && !writer.isAlive(), "OEM lock, callback and queued configuration deadlocked");
        if (failure.get() != null) throw failure.get();
        require(effects.get() == 0, "Queued configuration admitted new callback effects");
        runtime.close();
        hooks.close();
    }

    @FunctionalInterface private interface Work { void run() throws Throwable; }
    private static Thread thread(String name, AtomicReference<Throwable> failure, Work work) {
        Thread thread = new Thread(() -> {
            try { work.run(); }
            catch (Throwable caught) { failure.compareAndSet(null, caught); }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
    static final class Fixture { int value(int value) { return value + 1; } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
