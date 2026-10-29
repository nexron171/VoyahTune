package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.CloseOperation;

public final class ModuleScope implements AutoCloseable {
    private enum Phase {
        PREPARING, ACTIVE, CLOSED
    }

    @FunctionalInterface
    public interface ConfigurationAction {
        void apply() throws Exception;
    }

    public static final class CleanupException extends Exception {
        CleanupException(Throwable cause) {
            super("Module cleanup requires process restart", cause);
        }
    }

    @FunctionalInterface
    public interface Scheduler {
        AutoCloseable schedule(long delayMillis, Runnable task) throws Exception;
    }

    private final Scheduler scheduler;
    private final Consumer<Throwable> failureSink;
    private final AtomicReference<Throwable> healthFailure = new AtomicReference<>();
    private final AtomicInteger mutations = new AtomicInteger();
    private final CloseOperation closeOperation = new CloseOperation();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Object resourcesLock = new Object();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Cancellation> timers = new ArrayList<>();
    private volatile Phase phase = Phase.PREPARING;
    private long revision;
    private boolean configurationStarted;

    public ModuleScope(Scheduler scheduler, long revision) {
        this(scheduler, revision, failure -> {});
    }

    public ModuleScope(Scheduler scheduler, long revision, Consumer<Throwable> failureSink) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.failureSink = Objects.requireNonNull(failureSink);
        this.revision = revision;
    }

    public void requireRestart(Throwable failure) {
        Objects.requireNonNull(failure);
        if (healthFailure.compareAndSet(null, failure)) failureSink.accept(failure);
    }

    private boolean active() { return phase == Phase.ACTIVE && healthFailure.get() == null; }
    public boolean stoppingOrFailed() { return phase == Phase.CLOSED || healthFailure.get() != null; }

    private boolean enterCallback() {
        return mutations.get() == 0 && lifecycle.readLock().tryLock();
    }

    private void checkHealth() {
        Throwable failure = healthFailure.get();
        if (failure != null) throw new IllegalStateException("Module requires process restart", failure);
    }

    public void own(AutoCloseable resource) throws Exception {
        Objects.requireNonNull(resource);
        synchronized (resourcesLock) {
            if (phase != Phase.CLOSED) {
                resources.add(resource);
                return;
            }
        }
        resource.close();
        throw new IllegalStateException("Module is disabled");
    }

    public HookRegistry.Callback callback(HookRegistry.Callback callback) {
        return invocation -> {
            // OEM callbacks may already hold a monitor needed by an active agent operation.
            if (!enterCallback()) return invocation.proceed();
            try {
                return active() ? callback.invoke(invocation) : invocation.proceed();
            } finally {
                lifecycle.readLock().unlock();
            }
        };
    }

    public Runnable guard(Runnable task) {
        final long scheduledRevision;
        lifecycle.readLock().lock();
        try {
            scheduledRevision = revision;
        } finally {
            lifecycle.readLock().unlock();
        }
        return () -> {
            if (!enterCallback()) return;
            try {
                if (active() && revision == scheduledRevision) {
                    task.run();
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        };
    }

    public Runnable listener(Runnable callback) {
        return () -> {
            if (!enterCallback()) return;
            try {
                if (active()) callback.run();
            } finally {
                lifecycle.readLock().unlock();
            }
        };
    }

    public AutoCloseable schedule(long delayMillis, Runnable task) throws Exception {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("Negative timer delay");
        }
        Cancellation cancellation;
        boolean active;
        lifecycle.readLock().lock();
        try {
            checkHealth();
            cancellation = new Cancellation(delayMillis, guard(Objects.requireNonNull(task)));
            synchronized (resourcesLock) {
                if (phase == Phase.CLOSED) {
                    throw new IllegalStateException("Module is disabled");
                }
                timers.add(cancellation);
            }
            active = phase == Phase.ACTIVE;
        } finally {
            lifecycle.readLock().unlock();
        }
        if (active) cancellation.start();
        return cancellation;
    }

    public void configure(long nextRevision, ConfigurationAction configuration) throws Exception {
        rejectCallbackMutation();
        List<Cancellation> pending;
        mutations.incrementAndGet();
        try { pending = configureOwned(nextRevision, configuration); }
        finally { mutations.decrementAndGet(); }
        for (Cancellation timer : pending) timer.start();
    }

    private List<Cancellation> configureOwned(long nextRevision, ConfigurationAction configuration) throws Exception {
        List<AutoCloseable> obsolete = new ArrayList<>();
        Exception configurationFailure = null;
        try {
            lifecycle.writeLock().lock();
            try {
                checkHealth();
                if (phase == Phase.CLOSED || nextRevision < revision) {
                    throw new IllegalStateException("Configuration revision cannot move backwards or enable a closed module");
                }
                phase = Phase.PREPARING;
                synchronized (resourcesLock) {
                    if (configurationStarted || nextRevision != revision) {
                        obsolete.addAll(timers);
                        timers.clear();
                    }
                }
                configurationStarted = true;
                revision = nextRevision;
                configuration.apply();
            } finally {
                lifecycle.writeLock().unlock();
            }
        } catch (Exception failure) {
            configurationFailure = failure;
            throw failure;
        } finally {
            try {
                closeAll(obsolete);
            } catch (Exception cleanupFailure) {
                if (configurationFailure != null) {
                    cleanupFailure.addSuppressed(configurationFailure);
                }
                throw cleanupFailure;
            }
        }
        List<Cancellation> pending;
        lifecycle.writeLock().lock();
        try {
            checkHealth();
            if (phase == Phase.CLOSED || revision != nextRevision) {
                throw new IllegalStateException("Module configuration could not commit");
            }
            phase = Phase.ACTIVE;
            synchronized (resourcesLock) {
                pending = new ArrayList<>(timers);
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
        return pending;
    }

    @Override
    public void close() throws Exception {
        rejectCallbackMutation();
        closeOperation.run(this::closeOwnedResources);
    }

    private void closeOwnedResources() throws Exception {
        mutations.incrementAndGet();
        try {
            lifecycle.writeLock().lock();
            try {
                if (phase == Phase.CLOSED) return;
                phase = Phase.CLOSED;
            } finally {
                lifecycle.writeLock().unlock();
            }
        } finally {
            mutations.decrementAndGet();
        }
        List<AutoCloseable> owned;
        synchronized (resourcesLock) {
            owned = new ArrayList<>(resources);
            owned.addAll(timers);
            resources.clear();
            timers.clear();
        }
        closeAll(owned);
    }

    private void rejectCallbackMutation() {
        if (lifecycle.getReadHoldCount() != 0) {
            throw new IllegalStateException("Module lifecycle must run outside its callbacks");
        }
    }

    static void closeAll(List<? extends AutoCloseable> owned) throws Exception {
        List<AutoCloseable> reversed = new ArrayList<>(owned);
        Collections.reverse(reversed);
        Throwable failure = null;
        for (AutoCloseable resource : reversed) {
            try {
                resource.close();
            } catch (Throwable caught) {
                if (failure == null) {
                    failure = caught;
                } else {
                    failure.addSuppressed(caught);
                }
            }
        }
        if (failure != null) {
            throw new CleanupException(failure);
        }
    }

    private final class Cancellation implements AutoCloseable {
        private final long delayMillis;
        private final Runnable action;
        private AutoCloseable delegate;
        private boolean cancelled;
        private boolean started;
        private boolean delegateClosed;
        private Throwable cancellationFailure;

        Cancellation(long delayMillis, Runnable action) {
            this.delayMillis = delayMillis;
            this.action = action;
        }

        void start() throws Exception {
            synchronized (this) {
                if (cancelled || started) return;
                started = true;
            }
            try {
                bind(scheduler.schedule(delayMillis, () -> {
                    synchronized (this) { if (cancelled) return; }
                    try {
                        action.run();
                    } finally {
                        synchronized (resourcesLock) {
                            timers.remove(this);
                        }
                    }
                }));
            } catch (Exception failure) {
                synchronized (resourcesLock) {
                    timers.remove(this);
                }
                close();
                throw failure;
            }
        }

        synchronized void bind(AutoCloseable delegate) throws Exception {
            this.delegate = Objects.requireNonNull(delegate);
            if (cancelled) close();
        }

        @Override
        public synchronized void close() throws Exception {
            cancelled = true;
            if (cancellationFailure instanceof Exception) throw (Exception) cancellationFailure;
            if (cancellationFailure instanceof Error) throw (Error) cancellationFailure;
            if (delegate != null && !delegateClosed) {
                try {
                    delegate.close();
                    delegateClosed = true;
                } catch (Exception | Error failure) {
                    cancellationFailure = failure;
                    requireRestart(failure);
                    throw failure;
                }
            }
            synchronized (resourcesLock) { timers.remove(this); }
        }
    }
}
