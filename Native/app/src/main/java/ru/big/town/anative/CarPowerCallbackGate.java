package ru.big.town.anative;

/** Android-free generation gate for CarPower listener callbacks across reconnect and teardown. */
final class CarPowerCallbackGate {
    static final long REJECTED_GENERATION = -1L;

    private long nextGeneration;
    private long activeGeneration;
    private boolean closed;

    synchronized long beginRegistration() {
        if (closed) return REJECTED_GENERATION;
        activeGeneration = ++nextGeneration;
        return activeGeneration;
    }

    synchronized boolean isCurrent(long generation) {
        return !closed && generation > 0L && generation == activeGeneration;
    }

    synchronized void invalidate(long generation) {
        if (generation == activeGeneration) activeGeneration = 0L;
    }

    synchronized void invalidateCurrent() {
        activeGeneration = 0L;
    }

    synchronized void close() {
        closed = true;
        activeGeneration = 0L;
    }
}
