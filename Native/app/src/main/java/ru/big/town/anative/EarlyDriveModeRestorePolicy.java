package ru.big.town.anative;

/** One 30-second observation window per wake, with no restart on repeated wake/connect events. */
final class EarlyDriveModeRestorePolicy {
    static final long WINDOW_MS = 30_000L;
    enum Decision { WAIT, MATCH, RESTORE_ECO, STOP }

    private long generation = -1L;
    private boolean active;
    private long firstCheckAt = -1L;
    private String target;

    synchronized boolean activate(long wakeGeneration) {
        if (wakeGeneration <= generation) return false;
        generation = wakeGeneration;
        active = true;
        firstCheckAt = -1L;
        target = null;
        return true;
    }

    /** Also consumes a wake whose first activation has not arrived yet. */
    synchronized boolean stop(long wakeGeneration) {
        if (wakeGeneration < generation) return false;
        generation = wakeGeneration;
        active = false;
        return true;
    }

    synchronized boolean beginCheck(long wakeGeneration, long now) {
        if (!isActive(wakeGeneration, now)) return false;
        if (firstCheckAt < 0L) firstCheckAt = now;
        return true;
    }

    synchronized boolean isActive(long wakeGeneration, long now) {
        if (generation != wakeGeneration || !active) return false;
        if (firstCheckAt >= 0L && now - firstCheckAt >= WINDOW_MS) active = false;
        return active;
    }

    /** A changed/disabled saved selection ends this run instead of retargeting queued work. */
    synchronized boolean acceptTarget(long wakeGeneration, long now,
                                      boolean enabled, String selectedMode) {
        if (!isActive(wakeGeneration, now)) return false;
        if (!enabled || !DriveModeCanPolicy.isSupported(selectedMode)
                || (target != null && !target.equals(selectedMode))) {
            active = false;
            return false;
        }
        target = selectedMode;
        return true;
    }

    synchronized Decision observe(long wakeGeneration, long now, Integer state) {
        if (!isActive(wakeGeneration, now)) return Decision.STOP;
        if (target == null || state == null) return Decision.WAIT;
        ModeFeedbackDecoder.Feedback feedback = ModeFeedbackDecoder.decode(
                ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID, state);
        if (feedback == null) return Decision.WAIT;
        if (target.equals(feedback.mode)) return Decision.MATCH;
        if ("ECO".equals(feedback.mode)) return Decision.RESTORE_ECO;
        active = false;
        return Decision.STOP;
    }

    synchronized long nextDelay(long wakeGeneration, long now, long checkStartedAt) {
        if (!isActive(wakeGeneration, now)) return -1L;
        // No overlapping requests or catch-up bursts after a slow OEM/provider call.
        long interval = Math.max(1_000L - (now - checkStartedAt), 1L);
        return Math.min(interval, WINDOW_MS - (now - firstCheckAt));
    }
}
