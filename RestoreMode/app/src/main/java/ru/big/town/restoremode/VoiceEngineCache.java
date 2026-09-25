package ru.big.town.restoremode;

/** Resource operations run on one worker; the retention flag can change on the UI thread. */
final class VoiceEngineCache<T extends AutoCloseable> {
    interface Factory<T> { T create() throws Exception; }
    private volatile boolean retained;
    private T engine;

    void retain(boolean value) { retained = value; }
    boolean retained() { return retained; }

    T acquire(Factory<T> factory) throws Exception {
        if (engine == null) engine = factory.create();
        return engine;
    }

    void releaseIfUnretained() {
        if (!retained) release();
    }

    void release() {
        T old = engine;
        engine = null;
        if (old != null) {
            try { old.close(); }
            catch (Exception e) { throw new IllegalStateException("Cannot release voice engine", e); }
        }
    }
}
