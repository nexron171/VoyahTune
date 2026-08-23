package ru.big.town.anative;

import java.lang.ref.WeakReference;

/** Process-wide latest host lease; stale Activity teardown cannot release its successor. */
final class SplitHostHostLease<T> {
    static final class Registration<T> {
        final long generation;
        final T previousOwner;

        Registration(long generation, T previousOwner) {
            this.generation = generation;
            this.previousOwner = previousOwner;
        }
    }

    private long generation;
    private WeakReference<T> owner = new WeakReference<>(null);

    synchronized Registration<T> acquire(T newOwner) {
        if (newOwner == null) throw new IllegalArgumentException("owner required");
        T previous = owner.get();
        owner = new WeakReference<>(newOwner);
        return new Registration<>(++generation, previous);
    }

    synchronized void release(long releasedGeneration) {
        if (releasedGeneration != generation) return;
        owner.clear();
        owner = new WeakReference<>(null);
    }
}
