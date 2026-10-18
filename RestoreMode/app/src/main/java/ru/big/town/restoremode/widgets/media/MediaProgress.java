package ru.big.town.restoremode.widgets.media;

/** Position and duration helpers shared by the media card and its JVM tests. */
public final class MediaProgress {
    private MediaProgress() {}

    /** Playback position at {@code now}, extended only while the player is actually playing. */
    public static long position(
            long reportedPosition, long updatedAt, long duration, boolean playing, long now) {
        long position = Math.max(0L, reportedPosition);
        if (playing && updatedAt > 0L && now > updatedAt) {
            position += now - updatedAt;
        }
        return duration > 0L ? Math.min(position, duration) : position;
    }

    public static float fraction(long position, long duration) {
        if (duration <= 0L) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) position / duration));
    }

    public static String format(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        return hours > 0L
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(java.util.Locale.US, "%d:%02d", minutes, rest);
    }
}
