package ru.big.town.anative;

/** Evaluated at delivery time so disabling notices also suppresses pending work. */
final class ActionNoticeGate {
    private String previous = "";
    private long until;

    boolean accept(
            boolean notificationsEnabled,
            boolean configurable,
            String text,
            long now,
            long duration) {
        if ((configurable && !notificationsEnabled) || text == null || text.isEmpty()) return false;
        if (text.equals(previous) && now < until) return false;
        previous = text;
        until = now + duration;
        return true;
    }
}
