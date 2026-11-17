package ru.big.town.restoremode.voice.commands;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Builds outside its lock, but never publishes a result from before a settings invalidation. */
final class VoiceCommandCache<T> {
    private final long ttl;
    private final LongSupplier clock;
    private long generation, cachedAt;
    private boolean refreshing;
    private T cached;

    VoiceCommandCache(long ttl, LongSupplier clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    T load(Supplier<T> build) {
        for (; ; ) {
            final long revision;
            synchronized (this) {
                if (cached != null && clock.getAsLong() - cachedAt < ttl) {
                    return cached;
                }
                revision = generation;
            }
            T fresh = build.get();
            synchronized (this) {
                if (revision != generation) {
                    continue;
                }
                cached = fresh;
                cachedAt = clock.getAsLong();
                return cached;
            }
        }
    }

    /**
     * Serves the cached value even after it expired and schedules one background rebuild. Null
     * means nothing was built since the last invalidation, so the caller decides whether building
     * inline is affordable.
     */
    synchronized T serveStaleAndRefresh(Supplier<T> build, Executor refresh) {
        T value = cached;
        if (value != null && !refreshing && clock.getAsLong() - cachedAt >= ttl) {
            refreshing = true;
            refresh.execute(
                    () -> {
                        try {
                            load(build);
                        } finally {
                            synchronized (this) {
                                refreshing = false;
                            }
                        }
                    });
        }
        return value;
    }

    synchronized void invalidate() {
        generation++;
        cached = null;
    }
}
