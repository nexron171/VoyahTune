package ru.big.town.anative;

/**
 * Small generation-fenced retry budget for event-driven recovery paths.
 *
 * <p>A real external event opens a new scope. Failures may consume a fixed number of retries
 * inside that scope, but no timer can renew the budget by itself.</p>
 */
final class EventRetryBudget {
    private final int maximum;
    private long scope = Long.MIN_VALUE;
    private int claimed;
    private boolean closed;

    EventRetryBudget(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("maximum must be >= 0");
        this.maximum = maximum;
    }

    synchronized void reset(long newScope) {
        if (closed) return;
        scope = newScope;
        claimed = 0;
    }

    synchronized boolean claim(long candidateScope) {
        if (closed || candidateScope != scope || claimed >= maximum) return false;
        claimed++;
        return true;
    }

    synchronized boolean isCurrent(long candidateScope) {
        return !closed && candidateScope == scope;
    }

    synchronized int claimedForTest() {
        return claimed;
    }

    synchronized void close() {
        closed = true;
        scope = Long.MIN_VALUE;
        claimed = maximum;
    }
}
