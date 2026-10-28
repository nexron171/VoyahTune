package ru.voyahtune.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class HookRegistryTest {
    private static boolean nativeBackend;

    public static void main(String[] arguments) throws Throwable {
        verify(arguments.length == 1 && arguments[0].equals("native"));
        System.out.println("HookRegistry: 8 behavior tests passed");
    }

    public static void verify(boolean useNative) throws Throwable {
        nativeBackend = useNative;
        callbacksShareOneOriginal();
        callbackFailurePreservesOriginal();
        originalExceptionKeepsIdentity();
        retirementWaitsForCallback(false);
        retirementWaitsForCallback(true);
        rejectedHookCanBeRetried();
        nestedOriginalRetainsCallbacks();
        replacementFailureDoesNotReplayOriginal();
    }

    private static void callbacksShareOneOriginal() throws Throwable {
        Backend backend = new Backend();
        HookRegistry registry = new HookRegistry(backend);
        Fixture fixture = new Fixture();
        Method target = Fixture.class.getDeclaredMethod("increment", int.class);
        List<String> trace = new ArrayList<>();
        AtomicReference<Object> actualReceiver = new AtomicReference<>();
        AutoCloseable first = registry.install("first", target, invocation -> {
            trace.add("first-before");
            invocation.arguments()[0] = 7;
            Object result = invocation.proceed();
            equal(result, invocation.proceed());
            trace.add("first-after");
            return (Integer) result + 100;
        });
        AutoCloseable second = registry.install("second", target, invocation -> {
            trace.add("second");
            actualReceiver.set(invocation.receiver());
            return invocation.proceed();
        });
        equal(112, backend.call(fixture, 1));
        equal(1, fixture.calls);
        equal(fixture, actualReceiver.get());
        equal(Arrays.asList("first-before", "second", "first-after"), trace);
        equal(1, backend.installs);
        first.close();
        equal(8, backend.call(fixture, 3));
        equal(0, backend.removals);
        second.close();
        second.close();
        equal(1, backend.removals);
        AutoCloseable third = registry.install("third", target, HookRegistry.Invocation::proceed);
        registry.close();
        third.close();
        equal(2, backend.removals);
    }

    private static void callbackFailurePreservesOriginal() throws Throwable {
        for (boolean afterOriginal : new boolean[] {false, true}) {
            Backend backend = new Backend();
            HookRegistry registry = new HookRegistry(backend);
            Fixture fixture = new Fixture();
            Method target = Fixture.class.getDeclaredMethod("increment", int.class);
            AutoCloseable handle = registry.install("broken", target, invocation -> {
                if (afterOriginal) {
                    invocation.proceed();
                }
                throw new IllegalStateException("callback-failure");
            });
            equal(12, backend.call(fixture, 7));
            equal(1, fixture.calls);
            handle.close();
        }
    }

    private static void rejectedHookCanBeRetried() throws Throwable {
        Backend backend = new Backend();
        backend.rejectNext = true;
        HookRegistry registry = new HookRegistry(backend);
        Method target = Fixture.class.getDeclaredMethod("increment", int.class);
        try {
            registry.install("rejected", target, HookRegistry.Invocation::proceed);
            throw new AssertionError("Rejected hook was accepted");
        } catch (IllegalStateException rejection) {
            require(!registry.requiresRestart(), "Clean rejection quarantined a process");
        }
        AutoCloseable handle = registry.install("accepted", target, HookRegistry.Invocation::proceed);
        equal(8, backend.call(new Fixture(), 3));
        handle.close();
        equal(1, backend.removals);
    }

    private static void nestedOriginalRetainsCallbacks() throws Throwable {
        Backend backend = new Backend();
        HookRegistry registry = new HookRegistry(backend);
        Fixture original = new Fixture();
        Fixture fromCallback = new Fixture();
        original.recursive = backend;
        List<Integer> callbacks = new ArrayList<>();
        Method target = Fixture.class.getDeclaredMethod("recurse", int.class);
        try (AutoCloseable handle = registry.install("nested", target, invocation -> {
            callbacks.add((Integer) invocation.arguments()[0]);
            equal(0, backend.call(fromCallback, 0));
            return invocation.proceed();
        })) {
            equal(2, backend.call(original, 2));
            equal(List.of(2, 1, 0), callbacks);
            equal(3, original.calls);
            equal(3, fromCallback.calls);
        }
    }

    private static void replacementFailureDoesNotReplayOriginal() throws Throwable {
        Backend backend = new Backend();
        HookRegistry registry = new HookRegistry(backend);
        Fixture fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("External replacement already attempted");
        int[] externalCalls = {0};
        Method target = Fixture.class.getDeclaredMethod("increment", int.class);
        try (AutoCloseable handle = registry.install("replacement", target, invocation -> {
            invocation.disableFallback();
            externalCalls[0]++;
            throw failure;
        })) {
            try { backend.call(fixture, 1); throw new AssertionError("Replacement error swallowed"); }
            catch (RuntimeException caught) { equal(failure, caught); }
            equal(1, externalCalls[0]);
            equal(0, fixture.calls);
        }
    }

    private static void originalExceptionKeepsIdentity() throws Throwable {
        Backend backend = new Backend();
        HookRegistry registry = new HookRegistry(backend);
        Fixture fixture = new Fixture();
        Method target = Fixture.class.getDeclaredMethod("fail");
        AutoCloseable handle = registry.install("throwing", target, HookRegistry.Invocation::proceed);
        try {
            backend.call(fixture);
            throw new AssertionError("Original failure was swallowed");
        } catch (IllegalArgumentException failure) {
            equal(fixture.failure, failure);
            equal(1, fixture.calls);
        }
        handle.close();
    }

    private static void retirementWaitsForCallback(boolean retainAnotherOwner) throws Throwable {
        Backend backend = new Backend();
        HookRegistry registry = new HookRegistry(backend);
        Fixture fixture = new Fixture();
        Method target = Fixture.class.getDeclaredMethod("increment", int.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AutoCloseable handle = registry.install("blocking", target, invocation -> {
            entered.countDown();
            release.await();
            return invocation.proceed();
        });
        AutoCloseable other = retainAnotherOwner
                ? registry.install("other", target, HookRegistry.Invocation::proceed) : null;
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                equal(6, backend.call(fixture, 1));
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        caller.start();
        require(entered.await(2, TimeUnit.SECONDS), "Callback did not enter");
        Runnable closing = () -> {
            try {
                handle.close();
                closeReturned.countDown();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        };
        Thread closer = new Thread(closing);
        Thread repeatedCloser = new Thread(closing);
        Thread registryCloser = retainAnotherOwner ? null : new Thread(() -> {
            try {
                registry.close();
                closeReturned.countDown();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        closer.start();
        repeatedCloser.start();
        if (registryCloser != null) {
            registryCloser.start();
        }
        boolean premature = closeReturned.await(50, TimeUnit.MILLISECONDS);
        release.countDown();
        caller.join(2000);
        closer.join(2000);
        repeatedCloser.join(2000);
        if (registryCloser != null) {
            registryCloser.join(2000);
        }
        require(!premature, "Concurrent close acknowledged a running callback");
        require(!caller.isAlive() && !closer.isAlive() && !repeatedCloser.isAlive()
                && (registryCloser == null || !registryCloser.isAlive()), "Retirement deadlocked");
        if (failure.get() != null) {
            throw failure.get();
        }
        equal(1, fixture.calls);
        equal(retainAnotherOwner ? 0 : 1, backend.removals);
        if (other != null) {
            equal(7, backend.call(fixture, 2));
            other.close();
        }
    }

    private static void equal(Object expected, Object actual) {
        require(java.util.Objects.equals(expected, actual), "Expected " + expected + ", received " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Backend implements HookRegistry.Backend {
        HookRegistry.Dispatch dispatch;
        Method target;
        int installs;
        int removals;
        boolean rejectNext;
        final CountDownLatch removed = new CountDownLatch(1);

        @Override
        public Method hook(Executable executable, HookRegistry.Dispatch callback, Method callbackMethod) {
            Method target = (Method) executable;
            installs++;
            dispatch = callback;
            this.target = target;
            target.setAccessible(true);
            if (rejectNext) {
                rejectNext = false;
                return null;
            }
            return nativeBackend ? HookBridge.hook(target, callback, callbackMethod) : target;
        }

        @Override
        public boolean unhook(Executable executable) {
            Method target = (Method) executable;
            boolean success = !nativeBackend || HookBridge.unhook(target);
            removals++;
            removed.countDown();
            return success;
        }

        Object call(Object... arguments) throws Throwable {
            if (!nativeBackend) {
                return dispatch.onCall(arguments);
            }
            try {
                return target.invoke(arguments[0], Arrays.copyOfRange(arguments, 1, arguments.length));
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static final class Fixture {
        int calls;
        Backend recursive;
        final IllegalArgumentException failure = new IllegalArgumentException("original-failure");

        private int increment(int value) {
            calls++;
            return value + 5;
        }

        private void fail() {
            calls++;
            throw failure;
        }

        private int recurse(int remaining) throws Throwable {
            calls++;
            return remaining == 0 ? 0 : (Integer) recursive.call(this, remaining - 1) + 1;
        }
    }
}
