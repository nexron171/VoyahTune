package ru.big.town.anative;

final class NavigationAudioDucking implements AutoCloseable {
    interface Focus {
        boolean request() throws Exception;

        default boolean alive() {
            return true;
        }

        void release() throws Exception;
    }

    private static final long RELEASE_DELAY_MILLIS = 250;
    private static final long RETRY_DELAY_MILLIS = 500;
    private final Focus focus;
    private boolean requested;
    private boolean granted;
    private long retryAt;
    private boolean closed;
    private long releaseAt = Long.MAX_VALUE;

    NavigationAudioDucking(Focus focus) {
        this.focus = focus;
    }

    void update(boolean playing, long now) throws Exception {
        if (closed) return;
        if (playing) {
            releaseAt = Long.MAX_VALUE;
            if (granted && !focus.alive()) granted = false;
            if (!granted && now >= retryAt) {
                // Even a failed Binder reply may leave an OEM request to release.
                requested = true;
                retryAt = now + RETRY_DELAY_MILLIS;
                granted = focus.request();
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
        granted = false;
        retryAt = 0;
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
