package ru.big.town.anative;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class ApplicationStartupSequence {
    private final BiConsumer<String, Runnable> launch;
    private final BiConsumer<Runnable, Long> schedule;
    private boolean started;
    private boolean cancelled;

    ApplicationStartupSequence(
            BiConsumer<String, Runnable> launch, BiConsumer<Runnable, Long> schedule) {
        this.launch = launch;
        this.schedule = schedule;
    }

    void start(List<String> actions, long delayMillis) {
        if (started || cancelled) return;
        started = true;
        step(new ArrayList<>(actions), Math.max(0, delayMillis), 0);
    }

    void cancel() {
        cancelled = true;
    }

    private void step(List<String> actions, long delayMillis, int index) {
        if (cancelled || index >= actions.size()) return;
        AtomicBoolean advanced = new AtomicBoolean();
        Runnable next =
                () -> {
                    if (advanced.compareAndSet(false, true)
                            && index + 1 < actions.size()
                            && !cancelled) {
                        schedule.accept(() -> step(actions, delayMillis, index + 1), delayMillis);
                    }
                };
        try {
            launch.accept(actions.get(index), next);
        } catch (RuntimeException ignored) {
            // An unavailable app must not prevent the remaining entries from starting.
            next.run();
        }
    }
}
