package ru.big.town.anative;

/**
 * Android-free absolute-time cursor for a door pause fade.
 *
 * <p>Only the next meaningful action is exposed: repeated rounded volumes and intermediate steps
 * whose deadlines already passed are skipped instead of being replayed as a burst.</p>
 */
final class DoorPauseFadeCursor {
    enum Kind { WRITE, WAIT, RESTORE }

    static final class Action {
        final Kind kind;
        final int volume;
        final long delayMs;

        private Action(Kind kind, int volume, long delayMs) {
            this.kind = kind;
            this.volume = volume;
            this.delayMs = delayMs;
        }
    }

    private final int startVolume;
    private final int steps;
    private final long fadeTotalMs;
    private final long restoreDeadlineMs;
    private int lastAttemptedVolume;

    DoorPauseFadeCursor(int startVolume, int steps, long fadeTotalMs, long remoteDrainMs) {
        if (startVolume <= 0 || steps <= 0) {
            throw new IllegalArgumentException("positive volume and steps required");
        }
        this.startVolume = startVolume;
        this.steps = steps;
        this.fadeTotalMs = Math.max(0L, fadeTotalMs);
        this.restoreDeadlineMs = DoorPauseTimeline.restoreDelayMs(
                this.fadeTotalMs, remoteDrainMs);
        this.lastAttemptedVolume = startVolume;
    }

    Action actionAt(long elapsedMs) {
        long elapsed = Math.max(0L, elapsedMs);
        if (elapsed >= restoreDeadlineMs) return restore();

        int currentStep = stepAt(elapsed);
        int currentVolume = volumeAtStep(currentStep);
        if (currentVolume != lastAttemptedVolume) return write(currentVolume);

        for (int step = currentStep + 1; step <= steps; step++) {
            int futureVolume = volumeAtStep(step);
            if (futureVolume == lastAttemptedVolume) continue;
            long deadline = DoorPauseTimeline.fadeStepDelayMs(step, steps, fadeTotalMs);
            if (deadline > elapsed) return waitFor(deadline - elapsed);
        }
        return waitFor(Math.max(0L, restoreDeadlineMs - elapsed));
    }

    void markAttempted(int volume) {
        lastAttemptedVolume = volume;
    }

    long capDelayToRestore(long elapsedMs, long requestedDelayMs) {
        long remaining = Math.max(0L, restoreDeadlineMs - Math.max(0L, elapsedMs));
        return Math.min(Math.max(0L, requestedDelayMs), remaining);
    }

    private int stepAt(long elapsed) {
        int current = 0;
        for (int step = 1; step <= steps; step++) {
            if (DoorPauseTimeline.fadeStepDelayMs(step, steps, fadeTotalMs) > elapsed) break;
            current = step;
        }
        return current;
    }

    private int volumeAtStep(int step) {
        return step <= 0
                ? startVolume
                : DoorPauseTimeline.fadeStepVolume(startVolume, step, steps);
    }

    private static Action write(int volume) {
        return new Action(Kind.WRITE, volume, 0L);
    }

    private static Action waitFor(long delayMs) {
        return new Action(Kind.WAIT, 0, delayMs);
    }

    private static Action restore() {
        return new Action(Kind.RESTORE, 0, 0L);
    }
}
