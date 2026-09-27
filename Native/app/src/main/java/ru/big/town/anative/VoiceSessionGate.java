package ru.big.town.anative;

/** Session cancellation and duplicate suppression, including commands waiting in the CAN queue. */
final class VoiceSessionGate {
    private String current;
    private int next;
    synchronized void begin(String token) { current = token; next = 0; }
    synchronized void cancel(String token) { if (token.equals(current)) current = null; }
    synchronized boolean submit(String token) { return submit(token, 0); }
    /** Sequence segments are accepted strictly in order; a repeated or skipped index is refused. */
    synchronized boolean submit(String token, int index) {
        if (!token.equals(current) || index != next) return false;
        next++;
        return true;
    }
    synchronized boolean active(String token) { return token.equals(current); }
    synchronized void clear() { current = null; next = 0; }
}
