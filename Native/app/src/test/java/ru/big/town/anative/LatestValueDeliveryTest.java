package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class LatestValueDeliveryTest {
    @Test
    public void burstKeepsLatestValueAndOneTask() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        LatestValueDelivery<String> delivery = new LatestValueDelivery<>(executor, delivered::add);

        for (int i = 0; i < 100_000; i++) delivery.offer(1L, i, "v" + i);

        assertEquals(1, executor.tasks.size());
        executor.runAll();
        assertEquals(Arrays.asList("v99999"), delivered);
    }

    @Test
    public void lateOldGenerationCannotReplaceNewValue() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        LatestValueDelivery<String> delivery = new LatestValueDelivery<>(executor, delivered::add);

        delivery.offer(2L, 1L, "new");
        delivery.offer(1L, 100L, "old");
        executor.runAll();

        assertEquals(Arrays.asList("new"), delivered);
    }

    @Test
    public void terminalTokenCannotBeReplacedByLateNormalWrite() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        LatestValueDelivery<String> delivery = new LatestValueDelivery<>(executor, delivered::add);

        delivery.offer(2L, 1L, "normal");
        delivery.offer(3L, 2L, "terminal");
        delivery.offer(2L, 3L, "late-normal");
        executor.runAll();

        assertEquals(Arrays.asList("terminal"), delivered);
    }

    @Test
    public void updateDuringDeliveryGetsOneFollowUp() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        AtomicReference<LatestValueDelivery<String>> holder = new AtomicReference<>();
        holder.set(new LatestValueDelivery<>(executor, value -> {
            delivered.add(value);
            if ("first".equals(value)) {
                holder.get().offer(1L, 2L, "second");
                holder.get().offer(1L, 3L, "latest");
            }
        }));

        holder.get().offer(1L, 1L, "first");
        executor.runAll();

        assertEquals(Arrays.asList("first", "latest"), delivered);
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) { tasks.addLast(command); }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
