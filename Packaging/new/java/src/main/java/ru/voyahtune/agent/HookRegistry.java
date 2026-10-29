package ru.voyahtune.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class HookRegistry implements AutoCloseable {
    public static final class RejectedException extends IllegalStateException {
        RejectedException() {
            super("Native hook rejected the method");
        }
    }

    public static final class RestartRequiredException extends IllegalStateException {
        RestartRequiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface Backend {
        default void prepare(Executable target) throws Exception {}
        default void installed(Executable target) throws Exception {}
        Method hook(Executable target, Dispatch callback, Method callbackMethod);
        boolean unhook(Executable target);
    }

    @FunctionalInterface
    public interface Callback {
        Object invoke(Invocation invocation) throws Throwable;
    }

    private final Backend backend;
    private final Map<Executable, Dispatch> hooks = new HashMap<>();
    private final CloseOperation closeOperation = new CloseOperation();
    private boolean closed;

    public HookRegistry(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    public synchronized AutoCloseable install(String owner, Executable target, Callback callback) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(target);
        Objects.requireNonNull(callback);
        if (closed) {
            throw new IllegalStateException("Hook registry is closed");
        }
        Dispatch dispatch = hooks.get(target);
        if (dispatch == null) {
            try { backend.prepare(target); }
            catch (Exception failure) { throw new IllegalStateException("Caller coverage could not be prepared", failure); }
            dispatch = new Dispatch(target);
            hooks.put(target, dispatch);
            Method backup;
            try {
                Method replacement = Dispatch.class.getMethod("onCall", Object[].class);
                backup = backend.hook(target, dispatch, replacement);
            } catch (Throwable failure) {
                dispatch.initializationFailed(failure);
                throw new RestartRequiredException("Hook installation requires process restart", failure);
            }
            if (backup == null) {
                RejectedException rejection = new RejectedException();
                dispatch.initializationFailed(rejection);
                hooks.remove(target, dispatch);
                throw rejection;
            }
            try {
                dispatch.initialize(backup);
                backend.installed(target);
            } catch (Throwable failure) {
                dispatch.initializationFailed(failure);
                throw new RestartRequiredException("Hook backup requires process restart", failure);
            }
        }
        if (dispatch.retiring || dispatch.failure != null) {
            throw new RestartRequiredException("Hook requires process restart", dispatch.failure);
        }
        if (dispatch.callbacks.containsKey(owner)) {
            throw new IllegalStateException("Module already owns this hook: " + owner);
        }
        CallbackSlot slot = new CallbackSlot(callback);
        dispatch.callbacks.put(owner, slot);
        dispatch.publish();
        return new Registration(owner, target, dispatch, slot);
    }

    public synchronized boolean requiresRestart() {
        return hooks.values().stream().anyMatch(dispatch -> dispatch.failure != null);
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (hooks.values().stream().anyMatch(dispatch -> dispatch.depth.get() != 0)) {
                throw new IllegalStateException("Cannot close the registry from its callback");
            }
        }
        closeOperation.run(this::closeHooks);
    }

    private void closeHooks() throws Exception {
        List<Registration> registrations = new ArrayList<>();
        synchronized (this) {
            closed = true;
            hooks.forEach((method, dispatch) -> dispatch.callbacks.forEach(
                    (owner, slot) -> registrations.add(new Registration(owner, method, dispatch, slot))));
        }
        Exception failure = null;
        for (Registration registration : registrations) {
            try {
                registration.close();
            } catch (Exception caught) {
                if (failure == null) {
                    failure = caught;
                } else {
                    failure.addSuppressed(caught);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final class Registration implements AutoCloseable {
        private final String owner;
        private final Executable target;
        private final Dispatch dispatch;
        private final CallbackSlot slot;

        Registration(String owner, Executable target, Dispatch dispatch, CallbackSlot slot) {
            this.owner = owner;
            this.target = target;
            this.dispatch = dispatch;
            this.slot = slot;
        }

        @Override
        public void close() throws Exception {
            if (dispatch.depth.get() != 0) {
                throw new IllegalStateException("Cannot remove a hook from its callback");
            }
            slot.closeOperation.run(this::remove);
        }

        private void remove() throws Exception {
            boolean lastOwner;
            synchronized (HookRegistry.this) {
                if (dispatch.callbacks.get(owner) != slot) {
                    return;
                }
                slot.disable();
                dispatch.publish();
                lastOwner = dispatch.callbacks.values().stream().noneMatch(CallbackSlot::isEnabled);
                if (lastOwner) {
                    dispatch.retiring = true;
                }
            }
            slot.awaitIdle();
            if (lastOwner) {
                dispatch.closeOperation.run(this::removeNativeHook);
            }
            synchronized (HookRegistry.this) {
                dispatch.callbacks.remove(owner, slot);
            }
        }

        private void removeNativeHook() {
            dispatch.awaitIdle();
            try {
                if (!backend.unhook(target)) {
                    throw new IllegalStateException("Native unhook failed; process restart required");
                }
                synchronized (HookRegistry.this) {
                    hooks.remove(target, dispatch);
                }
            } catch (RuntimeException failure) {
                RestartRequiredException restart = new RestartRequiredException("Native unhook requires process restart", failure);
                dispatch.failure = restart;
                throw restart;
            }
        }
    }

    public static final class Dispatch {
        private final Executable target;
        private final boolean staticMethod;
        private final Map<String, CallbackSlot> callbacks = new LinkedHashMap<>();
        private final CountDownLatch initialized = new CountDownLatch(1);
        private final CloseOperation closeOperation = new CloseOperation();
        private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        private final ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);
        private volatile List<CallbackSlot> published = List.of();
        private volatile boolean retiring;
        private volatile Throwable failure;
        private OriginalCall original;
        private int inFlight;

        Dispatch(Executable target) {
            this.target = target;
            staticMethod = Modifier.isStatic(target.getModifiers());
        }

        void initialize(Method backup) {
            original = new OriginalCall(target, backup);
            initialized.countDown();
        }

        void initializationFailed(Throwable failure) {
            this.failure = failure;
            initialized.countDown();
        }

        void publish() {
            List<CallbackSlot> enabled = new ArrayList<>();
            callbacks.values().stream().filter(CallbackSlot::isEnabled).forEach(enabled::add);
            published = List.copyOf(enabled);
        }

        public Object onCall(Object[] packedArguments) throws Throwable {
            awaitInitialization();
            if (original == null) {
                throw new IllegalStateException("Hook backup unavailable", failure);
            }
            if (retiring || callbackDepth.get() != 0) {
                return original.invoke(packedArguments);
            }
            List<CallbackSlot> snapshot = null;
            synchronized (this) {
                if (!retiring) {
                    inFlight++;
                    snapshot = published;
                }
            }
            if (snapshot == null) {
                return original.invoke(packedArguments);
            }
            depth.set(depth.get() + 1);
            int previousCallbackDepth = callbackDepth.get();
            callbackDepth.set(previousCallbackDepth + 1);
            try {
                int offset = staticMethod ? 0 : 1;
                if (packedArguments.length < offset) {
                    throw new IllegalArgumentException("Instance callback has no receiver");
                }
                Object receiver = staticMethod ? null : packedArguments[0];
                Object[] arguments = Arrays.copyOfRange(packedArguments, offset, packedArguments.length);
                Callback originalCall = invocation -> {
                    Object[] packed = new Object[arguments.length + offset];
                    if (!staticMethod) {
                        packed[0] = receiver;
                    }
                    System.arraycopy(arguments, 0, packed, offset, arguments.length);
                    int previous = callbackDepth.get();
                    callbackDepth.remove();
                    try {
                        return original.invoke(packed);
                    } finally {
                        callbackDepth.set(previous);
                    }
                };
                return new Invocation(receiver, arguments, snapshot, originalCall, 0).proceed();
            } finally {
                depth.set(depth.get() - 1);
                if (previousCallbackDepth == 0) callbackDepth.remove(); else callbackDepth.set(previousCallbackDepth);
                synchronized (this) {
                    inFlight--;
                    notifyAll();
                }
            }
        }

        private void awaitInitialization() {
            boolean interrupted = false;
            for (;;) {
                try {
                    initialized.await();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void awaitIdle() {
            boolean interrupted = false;
            while (inFlight != 0) {
                try {
                    wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static final class Invocation {
        private final Object receiver;
        private final Object[] arguments;
        private final List<CallbackSlot> callbacks;
        private final Callback original;
        private final int index;
        private boolean proceeded;
        private boolean fallbackAllowed = true;
        private Object value;
        private Throwable failure;

        Invocation(Object receiver, Object[] arguments, List<CallbackSlot> callbacks,
                   Callback original, int index) {
            this.receiver = receiver;
            this.arguments = arguments;
            this.callbacks = callbacks;
            this.original = original;
            this.index = index;
        }

        public Object receiver() {
            return receiver;
        }

        public Object[] arguments() {
            return arguments;
        }

        public void disableFallback() {
            // A replacement that starts an external command must own its retry decision.
            fallbackAllowed = false;
        }

        public Object proceed() throws Throwable {
            if (!proceeded) {
                proceeded = true;
                try {
                    if (index == callbacks.size()) {
                        value = original.invoke(this);
                    } else {
                        Invocation next = new Invocation(receiver, arguments, callbacks, original, index + 1);
                        try {
                            value = callbacks.get(index).invoke(next);
                        } catch (Throwable callbackFailure) {
                            if (!next.fallbackAllowed) throw callbackFailure;
                            value = next.proceed();
                        }
                    }
                } catch (Throwable caught) {
                    failure = caught;
                }
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static final class CallbackSlot {
        private final Callback callback;
        private final CloseOperation closeOperation = new CloseOperation();
        private boolean enabled = true;
        private int inFlight;

        CallbackSlot(Callback callback) {
            this.callback = callback;
        }

        Object invoke(Invocation invocation) throws Throwable {
            boolean admitted;
            synchronized (this) {
                admitted = enabled;
                if (admitted) {
                    inFlight++;
                }
            }
            if (!admitted) {
                return invocation.proceed();
            }
            try {
                return callback.invoke(invocation);
            } finally {
                synchronized (this) {
                    inFlight--;
                    notifyAll();
                }
            }
        }

        synchronized void disable() {
            enabled = false;
        }

        synchronized boolean isEnabled() {
            return enabled;
        }

        synchronized void awaitIdle() {
            boolean interrupted = false;
            while (inFlight != 0) {
                try {
                    wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
