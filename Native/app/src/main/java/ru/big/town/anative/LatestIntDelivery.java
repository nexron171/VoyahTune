package ru.big.town.anative;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/** Thread-safe latest-value slot with at most one scheduled/running delivery. */
final class LatestIntDelivery implements AutoCloseable {
    private final Executor executor;
    private final IntConsumer listener;
    private final Runnable drainRunnable = this::drain;

    private int latestValue;
    private long offeredRevision;
    private boolean scheduled;
    private boolean closed;

    LatestIntDelivery(Executor executor, IntConsumer listener) {
        if (executor == null || listener == null) {
            throw new IllegalArgumentException("executor/listener required");
        }
        this.executor = executor;
        this.listener = listener;
    }

    void offer(int value) {
        boolean shouldSchedule = false;
        long scheduledRevision = 0L;
        synchronized (this) {
            if (closed) return;
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
        final int value;
        final long revision;
        synchronized (this) {
            if (closed) return;
            value = latestValue;
            revision = offeredRevision;
        }

        try {
            listener.accept(value);
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
