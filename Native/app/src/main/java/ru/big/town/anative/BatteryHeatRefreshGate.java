package ru.big.town.anative;

/** One-running plus one-merged follow-up gate for battery state/settings refreshes. */
final class BatteryHeatRefreshGate {
    static final class Request {
        final long epoch;
        final boolean evaluateAuto;
        final String reason;

        private Request(long epoch, boolean evaluateAuto, String reason) {
            this.epoch = epoch;
            this.evaluateAuto = evaluateAuto;
            this.reason = reason == null ? "" : reason;
        }
    }

    static final class Completion {
        final boolean publish;
        final Request next;

        private Completion(boolean publish, Request next) {
            this.publish = publish;
            this.next = next;
        }

        private static Completion empty() {
            return new Completion(false, null);
        }
    }

    private Request running;
    private Request pending;
    private boolean closed;

    /** Offers work and returns the request which should be submitted, or {@code null}. */
    synchronized Request offer(long epoch, boolean evaluateAuto, String reason) {
        if (closed) return null;
        Request offered = new Request(epoch, evaluateAuto, reason);
        if (running == null) {
            running = merge(pending, offered);
            pending = null;
            return running;
        }
        pending = merge(pending, offered);
        return null;
    }

    /** Accepts a result only when no newer request exists; otherwise starts one merged follow-up. */
    synchronized Completion finish(Request request) {
        if (closed || request == null || running != request) return Completion.empty();
        if (pending == null) {
            running = null;
            return new Completion(true, null);
        }
        Request next = merge(running, pending);
        running = next;
        pending = null;
        return new Completion(false, next);
    }

    /** Retains rejected work and any newer request for one bounded retry. */
    synchronized void reject(Request request) {
        if (closed || request == null || running != request) return;
        pending = merge(running, pending);
        running = null;
    }

    synchronized Request retry() {
        if (closed || running != null || pending == null) return null;
        running = pending;
        pending = null;
        return running;
    }

    synchronized void close() {
        closed = true;
        running = null;
        pending = null;
    }

    private static Request merge(Request older, Request newer) {
        if (older == null) return newer;
        if (newer == null) return older;
        if (older.epoch != newer.epoch) return newer;
        boolean evaluate = older.evaluateAuto || newer.evaluateAuto;
        String reason;
        if (newer.evaluateAuto || !older.evaluateAuto) reason = newer.reason;
        else reason = older.reason;
        return new Request(newer.epoch, evaluate, reason);
    }
}
