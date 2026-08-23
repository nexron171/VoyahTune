package ru.big.town.anative;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/** Thread-safe latest-value slot with at most one scheduled/running delivery. */
final class LatestIntDelivery implements AutoCloseable {
    interface Listener {
        void accept(long token, long revision, int value);
    }

    private final Executor executor;
    private final Listener listener;
    private final Runnable drainRunnable = this::drain;

    private long latestToken;
    private long latestRevision;
    private int latestValue;
    private long offeredRevision;
    private boolean scheduled;
    private boolean closed;

    LatestIntDelivery(Executor executor, IntConsumer listener) {
        this(executor, (token, revision, value) -> listener.accept(value));
    }

    LatestIntDelivery(Executor executor, Listener listener) {
        if (executor == null || listener == null) {
            throw new IllegalArgumentException("executor/listener required");
        }
        this.executor = executor;
        this.listener = listener;
    }

    void offer(int value) {
        offer(0L, 0L, value);
    }

    void offer(long token, int value) {
        offer(token, 0L, value);
    }

    void offer(long token, long revision, int value) {
        boolean shouldSchedule = false;
        long scheduledRevision = 0L;
        synchronized (this) {
            if (closed) return;
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
                if (closed) return;
                if (offeredRevision == scheduledRevision) {
                    scheduled = false;
                } else {
                    // An offer raced the rejected execute while scheduled was still true. It did
                    // not schedule for itself, so this call must carry that latest revision.
                    retryRevision = offeredRevision;
                }
            }
            if (retryRevision != 0L) schedule(retryRevision);
        }
    }

    private void drain() {
        final long token;
        final long valueRevision;
        final int value;
        final long revision;
        synchronized (this) {
            if (closed) return;
            token = latestToken;
            valueRevision = latestRevision;
            value = latestValue;
            revision = offeredRevision;
        }

        try {
            listener.accept(token, valueRevision, value);
        } catch (RuntimeException ignored) {
            // A bad consumer value must not poison future deliveries or its executor thread.
        } finally {
            boolean again;
            long nextRevision = 0L;
            synchronized (this) {
                if (closed) return;
                again = offeredRevision != revision;
                if (again) nextRevision = offeredRevision;
                else scheduled = false;
            }
            if (again) schedule(nextRevision);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduled = false;
    }
}
