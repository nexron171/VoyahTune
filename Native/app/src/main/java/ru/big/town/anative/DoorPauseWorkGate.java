package ru.big.town.anative;

/** Thread-safe drop-while-busy gate for the door media pause pipeline. */
final class DoorPauseWorkGate {
    static final int REJECTED_GENERATION = -1;

    private int generation;
    private boolean busy;
    private boolean fallbackClaimed;
    private boolean fallbackResolved;
    private boolean closed;

    synchronized int tryAcquire() {
        if (closed || busy || fallbackClaimed) return REJECTED_GENERATION;
        busy = true;
        fallbackResolved = false;
        return ++generation;
    }

    synchronized void release(int candidateGeneration) {
        if (!closed && candidateGeneration == generation) busy = false;
    }

    /** Remains true after release until a newer run starts, so late proxy results can be fenced. */
    synchronized boolean isLatest(int candidateGeneration) {
        return !closed && candidateGeneration == generation;
    }

    /** Atomically wins the late proxy fallback against a newer door-open run. */
    synchronized boolean tryClaimFallback(int candidateGeneration) {
        if (closed || candidateGeneration != generation || fallbackResolved) return false;
        fallbackResolved = true;
        fallbackClaimed = true;
        return true;
    }

    synchronized void finishFallback(int candidateGeneration) {
        if (candidateGeneration == generation) fallbackClaimed = false;
    }

    synchronized void acknowledgeProxy(int candidateGeneration) {
        if (!closed && candidateGeneration == generation) fallbackResolved = true;
    }

    synchronized void close() {
        closed = true;
        generation++;
        busy = false;
        fallbackClaimed = false;
        fallbackResolved = true;
    }

    synchronized boolean isBusy() {
        return busy;
    }
}
