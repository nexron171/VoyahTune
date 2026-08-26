package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent internal marker for the one outstanding wash-mode request bit. */
final class WashModeRequestLease {
    private static final String PREFS = "wash_mode_runtime";
    private static final String KEY_NEXT_GENERATION = "next_generation";
    private static final String KEY_ACTIVE_GENERATION = "active_generation";

    interface Store {
        Snapshot read();

        boolean write(long nextGeneration, long activeGeneration);
    }

    static final class Snapshot {
        final long nextGeneration;
        final long activeGeneration;

        Snapshot(long nextGeneration, long activeGeneration) {
            this.nextGeneration = nextGeneration;
            this.activeGeneration = activeGeneration;
        }
    }

    private final Store store;
    private long nextGeneration;
    private long activeGeneration;

    static WashModeRequestLease from(Context context) {
        Context deviceContext = context.getApplicationContext()
                .createDeviceProtectedStorageContext();
        SharedPreferences prefs = deviceContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new WashModeRequestLease(new Store() {
            @Override
            public Snapshot read() {
                return new Snapshot(
                        prefs.getLong(KEY_NEXT_GENERATION, 0L),
                        prefs.getLong(KEY_ACTIVE_GENERATION, 0L));
            }

            @Override
            public boolean write(long next, long active) {
                return prefs.edit()
                        .putLong(KEY_NEXT_GENERATION, next)
                        .putLong(KEY_ACTIVE_GENERATION, active)
                        .commit();
            }
        });
    }

    WashModeRequestLease(Store store) {
        if (store == null) throw new IllegalArgumentException("Wash lease store is null");
        this.store = store;
        Snapshot snapshot = store.read();
        if (snapshot == null) snapshot = new Snapshot(0L, 0L);
        nextGeneration = Math.max(0L, snapshot.nextGeneration);
        activeGeneration = Math.max(0L, snapshot.activeGeneration);
        if (nextGeneration < activeGeneration) nextGeneration = activeGeneration;
    }

    /** Arms a fresh generation. A zero result means that durable publication failed. */
    synchronized long arm() {
        long candidate = nextGeneration == Long.MAX_VALUE ? 1L : nextGeneration + 1L;
        if (!store.write(candidate, candidate)) return 0L;
        nextGeneration = candidate;
        activeGeneration = candidate;
        return candidate;
    }

    synchronized long activeGeneration() {
        return activeGeneration;
    }

    /** Clears only the exact generation observed by the completed cleanup operation. */
    synchronized boolean disarm(long generation) {
        if (generation <= 0L || activeGeneration != generation) return false;
        if (!store.write(nextGeneration, 0L)) return false;
        activeGeneration = 0L;
        return true;
    }
}
