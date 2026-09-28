package ru.big.town.anative;

/** Session cancellation and duplicate suppression, including commands waiting in the CAN queue. */
final class VoiceSessionGate {
    private String current;
    private boolean submitted;
    synchronized void begin(String token) { current = token; submitted = false; }
    synchronized void cancel(String token) { if (token.equals(current)) current = null; }
    synchronized boolean submit(String token) {
        if (!token.equals(current) || submitted) return false;
        submitted = true;
        return true;
    }
    synchronized boolean active(String token) { return token.equals(current); }
    synchronized void clear() { current = null; }
}
