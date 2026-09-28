package ru.big.town.restoremode;

/** Serializes cancellation against opening/reading/releasing a microphone; no model work under this lock. */
final class VoiceSessionControl {
    interface Capture {
        int read(short[] buffer, int offset, int length);
        void close();
    }
    interface Factory { Capture open() throws Exception; }
    private long generation;
    private Capture capture;

    synchronized long begin() {
        closeCapture();
        return ++generation;
    }
    synchronized boolean active(long token) { return token == generation; }
    synchronized boolean cancel(long token) {
        if (token != generation) return false;
        ++generation;
        closeCapture();
        return true;
    }
    synchronized void cancelAll() {
        ++generation;
        closeCapture();
    }
    synchronized boolean open(long token, Factory factory) throws Exception {
        if (!active(token)) return false;
        if (capture != null) throw new IllegalStateException("Microphone already open");
        capture = factory.open();
        return true;
    }
    synchronized int read(long token, short[] buffer, int offset, int length) {
        return active(token) && capture != null ? capture.read(buffer, offset, length) : 0;
    }
    synchronized void finishCapture(long token) {
        if (active(token)) closeCapture();
    }
    private void closeCapture() {
        Capture old = capture;
        capture = null;
        if (old != null) old.close();
    }
}
