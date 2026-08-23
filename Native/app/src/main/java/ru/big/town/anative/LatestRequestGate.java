package ru.big.town.anative;

/** Thread-safe one-running plus one-latest request gate. Values are compared by identity. */
final class LatestRequestGate<T> {
    private T latest;
    private T running;
    private boolean closed;

    /** Offers a request and returns the value which should be submitted, or {@code null}. */
    synchronized T offer(T value) {
        if (closed || value == null) return null;
        latest = value;
        if (running != null) return null;
        running = latest;
        return running;
    }

    /** Completes a worker run, accepting only the newest value and starting one follow-up. */
    synchronized Completion<T> finish(T value) {
        if (closed || value == null || running != value) return Completion.empty();
        boolean publish = latest == value;
        running = null;
        if (publish) latest = null;
        T next = null;
        if (latest != null) {
            running = latest;
            next = running;
        }
        return new Completion<>(publish, next);
    }

    /** Releases a request which could not be submitted while retaining the latest retry intent. */
    synchronized void reject(T value) {
        if (!closed && running == value) running = null;
    }

    /** Returns the retained latest request for a bounded retry, or {@code null}. */
    synchronized T retry() {
        if (closed || running != null || latest == null) return null;
        running = latest;
        return running;
    }

    synchronized void close() {
        closed = true;
        latest = null;
        running = null;
    }

    static final class Completion<T> {
        final boolean publish;
        final T next;

        private Completion(boolean publish, T next) {
            this.publish = publish;
            this.next = next;
        }

        private static <T> Completion<T> empty() {
            return new Completion<>(false, null);
        }
    }
}
