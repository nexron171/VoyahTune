package ru.big.town.anative;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Thread-safe generic latest-value slot with at most one scheduled/running delivery. */
final class LatestValueDelivery<T> {
    interface Listener<T> {
        void accept(long token, long revision, T value);
    }

    private final Executor executor;
    private final Listener<T> listener;
    private final Runnable drainRunnable = this::drain;

    private long latestToken;
    private long latestRevision;
    private T latestValue;
    private long offeredRevision;
    private boolean scheduled;

    LatestValueDelivery(Executor executor, Consumer<T> listener) {
        this(executor, (token, revision, value) -> listener.accept(value));
    }

    LatestValueDelivery(Executor executor, Listener<T> listener) {
        if (executor == null || listener == null) {
            throw new IllegalArgumentException("executor/listener required");
        }
        this.executor = executor;
        this.listener = listener;
    }

    void offer(long token, long revision, T value) {
        boolean shouldSchedule = false;
        long scheduledRevision = 0L;
        synchronized (this) {
            if (token > 0L && latestToken > 0L) {
                if (token < latestToken
                        || (token == latestToken && revision < latestRevision)) {
                    return;
                }
            }
            latestToken = token;
            latestRevision = revision;
            latestValue = value;
            offeredRevision++;
            if (!scheduled) {
                scheduled = true;
                shouldSchedule = true;
                scheduledRevision = offeredRevision;
            }
        }
        if (shouldSchedule) schedule(scheduledRevision);
    }

    private void schedule(long scheduledRevision) {
        try {
            executor.execute(drainRunnable);
        } catch (RuntimeException rejected) {
            long retryRevision = 0L;
            synchronized (this) {
                if (offeredRevision == scheduledRevision) {
                    scheduled = false;
                } else {
                    retryRevision = offeredRevision;
                }
            }
            if (retryRevision != 0L) schedule(retryRevision);
        }
    }

    private void drain() {
        final long token;
        final long valueRevision;
        final T value;
        final long revision;
        synchronized (this) {
            token = latestToken;
            valueRevision = latestRevision;
            value = latestValue;
            revision = offeredRevision;
        }

        try {
            listener.accept(token, valueRevision, value);
        } catch (RuntimeException ignored) {
            // A bad value must not poison the process-wide delivery lane.
        } finally {
            boolean again;
            long nextRevision = 0L;
            synchronized (this) {
                again = offeredRevision != revision;
                if (again) nextRevision = offeredRevision;
                else scheduled = false;
            }
            if (again) schedule(nextRevision);
        }
    }
}
