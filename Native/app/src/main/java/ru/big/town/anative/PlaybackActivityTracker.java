package ru.big.town.anative;

/** Tracks playback-class edges on the serial media callback ingress. */
final class PlaybackActivityTracker {
    enum Change {
        SAME,
        ENTERED_ACTIVE,
        LEFT_ACTIVE
    }

    private boolean active;

    PlaybackActivityTracker(boolean initiallyActive) {
        active = initiallyActive;
    }

    Change update(boolean nextActive) {
        if (nextActive == active) return Change.SAME;
        active = nextActive;
        return nextActive ? Change.ENTERED_ACTIVE : Change.LEFT_ACTIVE;
    }
}
