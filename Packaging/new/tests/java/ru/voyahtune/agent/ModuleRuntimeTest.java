package ru.voyahtune.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class ModuleRuntimeTest {
    public static void main(String[] arguments) throws Exception {
        linkageFailureRollsBackInstalledResources();
        rollbackClosesPartiallyInstalledModule();
        disableCancelsOwnedWork();
        configurationRejectsStaleTasks();
        changedCodeRequiresRestart();
        failedCleanupRequiresRestart();
        preparingModuleCannotAct();
        concurrentCloseWaitsForTheSameCleanup();
        failedConfigurationStillCancelsObsoleteTimers();
        failedCloseNeverBecomesSuccess();
        System.out.println("ModuleRuntime: 10 behavior tests passed");
    }

    private static void linkageFailureRollsBackInstalledResources() throws Exception {
        List<String> closed = new ArrayList<>();
        NoClassDefFoundError failure = new NoClassDefFoundError("missing.OemDependency");
        ModuleRuntime runtime = new ModuleRuntime("code", (delay, task) -> () -> {}, id -> new ModuleRuntime.Module() {
            @Override
            public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
                scope.own(() -> closed.add(id));
                throw failure;
            }
            @Override
            public void configure(ModuleScope scope, Map<String, String> configuration) {
            }
        });
        runtime.apply("code", 1, List.of("oem"), Map.of());
        equal(ModuleRuntime.Phase.FAILED, runtime.snapshot().phase);
        equal(failure, runtime.snapshot().failure);
        equal(List.of("oem"), closed);
        runtime.close();
        equal(ModuleRuntime.Phase.CLOSED, runtime.snapshot().phase);
    }

    private static void rollbackClosesPartiallyInstalledModule() {
        Harness harness = new Harness();
        harness.failModule = "two";
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, Arrays.asList("one", "two"), Map.of());
        equal(ModuleRuntime.Phase.FAILED, runtime.snapshot().phase);
        equal(0, harness.resources);
        equal(List.of("close-two", "close-one"), harness.closed);
    }

    private static void disableCancelsOwnedWork() throws Exception {
        Harness harness = new Harness();
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of());
        harness.scope.schedule(10, () -> harness.effects.add("late"));
        runtime.close();
        harness.scheduler.advanceEvenIfCancelled();
        equal(0, harness.resources);
        equal(List.of(), harness.effects);
        equal(ModuleRuntime.Phase.CLOSED, runtime.snapshot().phase);
    }

    private static void configurationRejectsStaleTasks() throws Exception {
        Harness harness = new Harness();
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of("value", "first"));
        Runnable stale = harness.scope.guard(() -> harness.effects.add("old"));
        harness.scope.schedule(10, () -> harness.effects.add("timer-old"));
        runtime.apply("code-v1", 2, List.of("one"), Map.of("value", "second"));
        runtime.apply("code-v1", 2, List.of("one"), Map.of("value", "second"));
        stale.run();
        harness.scheduler.advanceEvenIfCancelled();
        equal(List.of(), harness.effects);
        equal(Arrays.asList("first", "second"), harness.configurations);
        equal(1, harness.installations);
        equal(2L, runtime.snapshot().revision);
        runtime.close();
    }

    private static void changedCodeRequiresRestart() throws Exception {
        Harness harness = new Harness();
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of());
        runtime.apply("code-v2", 2, List.of("one"), Map.of());
        equal(ModuleRuntime.Phase.RESTART_REQUIRED, runtime.snapshot().phase);
        equal(1, harness.installations);
        runtime.close();
    }

    private static void failedCleanupRequiresRestart() {
        Harness harness = new Harness();
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of());
        harness.failCleanup = true;
        runtime.apply("code-v1", 2, List.of(), Map.of());
        equal(ModuleRuntime.Phase.RESTART_REQUIRED, runtime.snapshot().phase);
        equal(1, harness.resources);
    }

    private static void preparingModuleCannotAct() throws Exception {
        Harness harness = new Harness();
        harness.runWhilePreparing = true;
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of());
        equal(List.of(), harness.effects);
        runtime.close();
    }

    private static void concurrentCloseWaitsForTheSameCleanup() throws Exception {
        ModuleScope scope = new ModuleScope((delay, task) -> () -> {}, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scope.own(() -> {
            entered.countDown();
            release.await();
        });
        Thread first = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        Thread second = new Thread(() -> {
            try {
                scope.close();
                returned.countDown();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        first.start();
        if (!entered.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("Cleanup did not start");
        }
        second.start();
        boolean premature = returned.await(50, TimeUnit.MILLISECONDS);
        release.countDown();
        first.join(2000);
        second.join(2000);
        if (premature || first.isAlive() || second.isAlive() || failure.get() != null) {
            throw new AssertionError("Concurrent close did not wait for cleanup", failure.get());
        }
    }

    private static void failedConfigurationStillCancelsObsoleteTimers() throws Exception {
        Harness harness = new Harness();
        ModuleRuntime runtime = harness.runtime();
        runtime.apply("code-v1", 1, List.of("one"), Map.of());
        harness.scope.schedule(10, () -> harness.effects.add("old"));
        harness.failConfigure = true;
        runtime.apply("code-v1", 2, List.of("one"), Map.of());
        equal(ModuleRuntime.Phase.FAILED, runtime.snapshot().phase);
        equal(1, harness.scheduler.cancelled);
        equal(0, harness.resources);
        harness.scheduler.advanceEvenIfCancelled();
        equal(List.of(), harness.effects);
    }

    private static void failedCloseNeverBecomesSuccess() throws Exception {
        ModuleScope scope = new ModuleScope((delay, task) -> () -> {}, 1);
        int[] calls = {0};
        scope.own(() -> {
            calls[0]++;
            throw new IllegalStateException("cleanup-failure");
        });
        Exception originalFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                scope.close();
                throw new AssertionError("Unconfirmed cleanup became success");
            } catch (Exception failure) {
                if (originalFailure == null) {
                    originalFailure = failure;
                } else {
                    equal(originalFailure, failure);
                }
            }
        }
        equal(1, calls[0]);
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", received " + actual);
        }
    }

    private static final class Scheduler implements ModuleScope.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        int cancelled;

        @Override
        public AutoCloseable schedule(long delayMillis, Runnable task) {
            tasks.add(task);
            return () -> cancelled++;
        }

        void advanceEvenIfCancelled() {
            List<Runnable> ready = new ArrayList<>(tasks);
            tasks.clear();
            ready.forEach(Runnable::run);
        }
    }

    private static final class Harness {
        final Scheduler scheduler = new Scheduler();
        final List<String> closed = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        final List<String> configurations = new ArrayList<>();
        int resources;
        int installations;
        String failModule;
        boolean failCleanup;
        boolean runWhilePreparing;
        boolean failConfigure;
        ModuleScope scope;

        ModuleRuntime runtime() {
            return new ModuleRuntime("code-v1", scheduler, id -> new ModuleRuntime.Module() {
                @Override
                public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
                    Harness.this.scope = scope;
                    installations++;
                    resources++;
                    scope.own(() -> {
                        if (failCleanup) {
                            throw new IllegalStateException("cleanup-failure");
                        }
                        resources--;
                        closed.add("close-" + id);
                    });
                    if (runWhilePreparing) {
                        scope.guard(() -> effects.add("premature")).run();
                    }
                    if (id.equals(failModule)) {
                        throw new IllegalStateException("install-failure");
                    }
                }

                @Override
                public void configure(ModuleScope scope, Map<String, String> configuration) {
                    if (failConfigure) {
                        throw new IllegalStateException("configure-failure");
                    }
                    configurations.add(configuration.get("value"));
                }
            });
        }
    }
}
