package ru.big.town.anative;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Each terminal callback advances once; scheduling keeps synchronous actions off the call stack.
 */
final class OrderedActionSequence {
    private final List<String> actions;
    private final BiConsumer<String, Runnable> execute;
    private final Consumer<Runnable> schedule;
    private final Consumer<Boolean> completion;
    private final AtomicBoolean finished = new AtomicBoolean();

    private OrderedActionSequence(
            List<String> actions,
            BiConsumer<String, Runnable> execute,
            Consumer<Runnable> schedule,
            Consumer<Boolean> completion) {
        this.actions = new ArrayList<>(actions);
        this.execute = execute;
        this.schedule = schedule;
        this.completion = completion;
    }

    static void run(
            List<String> actions,
            BiConsumer<String, Runnable> execute,
            Consumer<Runnable> schedule,
            Consumer<Boolean> completion) {
        new OrderedActionSequence(actions, execute, schedule, completion).step(0);
    }

    private void step(int index) {
        if (finished.get()) return;
        if (index == actions.size()) {
            finish(true);
            return;
        }
        AtomicBoolean advanced = new AtomicBoolean();
        try {
            execute.accept(
                    actions.get(index),
                    () -> {
                        if (advanced.compareAndSet(false, true) && !finished.get()) {
                            schedule.accept(() -> step(index + 1));
                        }
                    });
        } catch (RuntimeException exception) {
            finish(false);
        }
    }

    private void finish(boolean accepted) {
        if (finished.compareAndSet(false, true)) completion.accept(accepted);
    }
}
