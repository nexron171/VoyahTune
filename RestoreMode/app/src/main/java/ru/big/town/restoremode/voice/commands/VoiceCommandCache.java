package ru.big.town.restoremode.voice.commands;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Builds outside its lock, but never publishes a result from before a settings invalidation. */
final class VoiceCommandCache<T> {
    private final long ttl;
    private final LongSupplier clock;
    private long generation, cachedAt;
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

    synchronized void invalidate() {
        generation++;
        cached = null;
    }
}
