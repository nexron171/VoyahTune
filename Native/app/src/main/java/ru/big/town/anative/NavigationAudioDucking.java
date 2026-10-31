package ru.big.town.anative;

final class NavigationAudioDucking implements AutoCloseable {
    interface Focus {
        void request() throws Exception;

        void release() throws Exception;
    }

    private static final long RELEASE_DELAY_MILLIS = 250;
    private final Focus focus;
    private boolean requested;
    private boolean closed;
    private long releaseAt = Long.MAX_VALUE;

    NavigationAudioDucking(Focus focus) {
        this.focus = focus;
    }

    void update(boolean playing, long now) throws Exception {
        if (closed) return;
        if (playing) {
            releaseAt = Long.MAX_VALUE;
            if (!requested) {
                // Even a failed Binder reply may leave an OEM request to release.
                requested = true;
                focus.request();
            }
        } else if (requested) {
            if (releaseAt == Long.MAX_VALUE) releaseAt = now + RELEASE_DELAY_MILLIS;
            if (now >= releaseAt) release();
        }
    }

    private void release() throws Exception {
        if (!requested) return;
        focus.release();
        requested = false;
        releaseAt = Long.MAX_VALUE;
    }

    boolean hasFocusRequest() {
        return requested;
    }

    @Override
    public void close() throws Exception {
        closed = true;
        release();
    }
}
