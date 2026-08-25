package ru.big.town.anative;

/**
 * Main-thread lifecycle fence for asynchronous SplitHost work.
 *
 * <p>The worker may finish after an Activity recreation, pause, pane release, or a newer launch.
 * Tokens issued here make those completions harmless without waiting for Binder work on main.</p>
 */
final class SplitHostGenerationGate {
    static final int LEFT = 0;
    static final int RIGHT = 1;
    static final long REJECTED = -1L;

    private final long hostGeneration;
    private final long[] paneGenerations = new long[] {1L, 1L};
    private long supervisionGeneration = 1L;
    private boolean resumed;
    private boolean closed;

    SplitHostGenerationGate(long hostGeneration) {
        this.hostGeneration = hostGeneration;
    }

    long hostGeneration() {
        return hostGeneration;
    }

    long resumeSupervision() {
        if (closed) return REJECTED;
        resumed = true;
        return ++supervisionGeneration;
    }

    void pauseSupervision() {
        resumed = false;
        supervisionGeneration++;
    }

    long currentSupervisionGeneration() {
        return supervisionGeneration;
    }

    boolean acceptsSupervision(long host, long generation) {
        return !closed && resumed && host == hostGeneration && generation == supervisionGeneration;
    }

    long nextPaneGeneration(int pane) {
        checkPane(pane);
        if (closed) return REJECTED;
        return ++paneGenerations[pane];
    }

    long currentPaneGeneration(int pane) {
        checkPane(pane);
        return paneGenerations[pane];
    }

    void invalidatePane(int pane) {
        checkPane(pane);
        paneGenerations[pane]++;
    }

    boolean acceptsPane(long host, int pane, long generation) {
        checkPane(pane);
        return !closed && host == hostGeneration && generation == paneGenerations[pane];
    }

    void close() {
        if (closed) return;
        closed = true;
        resumed = false;
        supervisionGeneration++;
        paneGenerations[LEFT]++;
        paneGenerations[RIGHT]++;
    }

    private static void checkPane(int pane) {
        if (pane != LEFT && pane != RIGHT) {
            throw new IllegalArgumentException("unknown pane " + pane);
        }
    }
}
