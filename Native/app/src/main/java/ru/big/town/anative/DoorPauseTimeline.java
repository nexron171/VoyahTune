package ru.big.town.anative;

/** Pure timing/math for door pause, kept free of Android dependencies for JVM tests. */
final class DoorPauseTimeline {
    private DoorPauseTimeline() {}

    static long fadeStepDelayMs(int step, int steps, long totalMs) {
        if (steps <= 0 || step <= 0) return 0L;
        int bounded = Math.min(step, steps);
        return Math.round(totalMs * (double) bounded / steps);
    }

    static int fadeStepVolume(int startVolume, int step, int steps) {
        if (startVolume <= 0 || steps <= 0) return 0;
        int bounded = Math.max(0, Math.min(step, steps));
        return Math.max(0, Math.round(startVolume * (float) (steps - bounded) / steps));
    }

    static long restoreDelayMs(long fadeTotalMs, long remoteDrainMs) {
        return Math.max(fadeTotalMs, remoteDrainMs);
    }
}
