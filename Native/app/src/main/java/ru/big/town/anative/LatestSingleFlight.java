package ru.big.town.anative;

/** IO-thread-confined gate: at most one running operation plus one coalesced latest request. */
final class LatestSingleFlight {
    private boolean running;
    private boolean pending;

    void request() {
        pending = true;
    }

    boolean tryStart() {
        if (running || !pending) return false;
        pending = false;
        running = true;
        return true;
    }

    void complete() {
        if (!running) throw new IllegalStateException("no operation is running");
        running = false;
    }
}
