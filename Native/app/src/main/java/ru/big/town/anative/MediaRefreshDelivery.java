package ru.big.town.anative;

import java.util.concurrent.Executor;

/**
 * Bounded priority slot for expensive media-session refreshes.
 *
 * <p>Many framework callbacks may arrive while one Binder-heavy refresh is running. They collapse
 * into at most one follow-up task, while stronger work can never be overwritten by a weaker request.
 * The supplied executor must enqueue asynchronously; production uses {@code Handler.post()}.
 */
final class MediaRefreshDelivery {
    enum Work {
        PUBLISH,
        REPICK,
        REBUILD
    }

    interface Listener {
        void accept(Work work, String reason);
    }

    private final Executor executor;
    private final Listener listener;
    private final Runnable drainRunnable = this::drain;

    private Work pendingWork;
    private String pendingReason = "";
    private long revision;
    private boolean scheduled;
    private boolean closed;

    MediaRefreshDelivery(Executor executor, Listener listener) {
        if (executor == null || listener == null) {
            throw new IllegalArgumentException("executor/listener required");
        }
        this.executor = executor;
        this.listener = listener;
    }

    void offer(Work work, String reason) {
        if (work == null) return;
        boolean shouldSchedule = false;
        long scheduledRevision = 0L;
        synchronized (this) {
            if (closed) return;
            if (pendingWork == null || work.ordinal() > pendingWork.ordinal()) {
                pendingWork = work;
                pendingReason = reason == null ? "" : reason;
            } else if (work == pendingWork) {
                pendingReason = reason == null ? "" : reason;
            }
            revision++;
            if (!scheduled) {
                scheduled = true;
                shouldSchedule = true;
                scheduledRevision = revision;
            }
        }
        if (shouldSchedule) schedule(scheduledRevision);
    }

    void close() {
        synchronized (this) {
            closed = true;
            pendingWork = null;
            pendingReason = "";
            scheduled = false;
        }
    }

    private void schedule(long scheduledRevision) {
        try {
            executor.execute(drainRunnable);
        } catch (RuntimeException rejected) {
            long retryRevision = 0L;
            synchronized (this) {
                if (closed) return;
                if (revision == scheduledRevision) {
                    scheduled = false;
                } else {
                    retryRevision = revision;
                }
            }
            if (retryRevision != 0L) schedule(retryRevision);
        }
    }

    private void drain() {
        final Work work;
        final String reason;
        synchronized (this) {
            if (closed || pendingWork == null) {
                scheduled = false;
                return;
            }
            work = pendingWork;
            reason = pendingReason;
            pendingWork = null;
            pendingReason = "";
        }

        try {
            listener.accept(work, reason);
        } catch (Throwable ignored) {
            // One failed refresh must not poison later media-session updates.
        } finally {
            long nextRevision = 0L;
            synchronized (this) {
                if (closed) {
                    scheduled = false;
                    pendingWork = null;
                    pendingReason = "";
                } else if (pendingWork == null) {
                    scheduled = false;
                } else {
                    nextRevision = revision;
                }
            }
            if (nextRevision != 0L) schedule(nextRevision);
        }
    }
}
