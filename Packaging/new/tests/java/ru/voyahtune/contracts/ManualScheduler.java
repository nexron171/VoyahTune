package ru.voyahtune.contracts;

import java.util.PriorityQueue;
import ru.voyahtune.agent.runtime.ModuleScope;

final class ManualScheduler implements ModuleScope.Scheduler {
    private final PriorityQueue<Task> pending = new PriorityQueue<>();
    private long now;
    private long sequence;

    long now() { return now; }
    long activeTasks() { return pending.stream().filter(task -> !task.cancelled).count(); }

    @Override
    public AutoCloseable schedule(long delayMillis, Runnable action) {
        Task task = new Task(now + delayMillis, sequence++, action);
        pending.add(task);
        return () -> task.cancelled = true;
    }

    void advance(long millis) {
        long end = now + millis;
        while (!pending.isEmpty() && pending.peek().due <= end) {
            Task task = pending.remove();
            now = task.due;
            if (!task.cancelled) {
                task.action.run();
            }
        }
        now = end;
    }

    private static final class Task implements Comparable<Task> {
        final long due;
        final long sequence;
        final Runnable action;
        boolean cancelled;

        Task(long due, long sequence, Runnable action) {
            this.due = due;
            this.sequence = sequence;
            this.action = action;
        }

        @Override
        public int compareTo(Task other) {
            int order = Long.compare(due, other.due);
            return order == 0 ? Long.compare(sequence, other.sequence) : order;
        }
    }
}
