package ru.big.town.anative;

import java.util.Arrays;
import java.util.function.Consumer;

/** Main-thread RSM polling; a slow read cannot accumulate more reads or undo newer events. */
final class LightDiagnosticsPoller {
    static final int UNKNOWN = Integer.MIN_VALUE;
    static final long INTERVAL_MS = 1_000L;

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    interface Reader {
        // Complete exactly once on the owning thread; null means the read failed.
        void read(Consumer<int[]> completion);
    }

    private final Scheduler scheduler;
    private final Reader reader;
    private final Consumer<int[]> listener;
    private final int[] values = new int[4];
    private final long[] revisions = new long[4];
    private final Runnable tick = this::poll;
    private boolean active;
    private boolean reading;
    private long generation;

    LightDiagnosticsPoller(Scheduler scheduler, Reader reader, Consumer<int[]> listener) {
        this.scheduler = scheduler;
        this.reader = reader;
        this.listener = listener;
        Arrays.fill(values, UNKNOWN);
    }

    void start() {
        stop();
        active = true;
        poll();
    }

    void stop() {
        active = false;
        ++generation;
        scheduler.removeCallbacks(tick);
        Arrays.fill(values, UNKNOWN);
        listener.accept(values.clone());
        // Keep reading set until completion, even across stop/start, to bound slow OEM work.
    }

    void onEvent(int index, int value) {
        if (!active) return;
        ++revisions[index];
        values[index] = value;
        listener.accept(values.clone());
    }

    private void poll() {
        if (!active) return;
        scheduler.postDelayed(tick, INTERVAL_MS);
        if (reading) return;
        reading = true;
        long session = generation;
        long[] beforeRead = revisions.clone();
        reader.read(snapshot -> {
            reading = false;
            if (!active || generation != session) return;
            for (int i = 0; i < values.length; i++) {
                if (revisions[i] == beforeRead[i]) {
                    values[i] = snapshot == null ? UNKNOWN : snapshot[i];
                }
            }
            listener.accept(values.clone());
        });
    }
}
