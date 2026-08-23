package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.Test;

public class LatestIntDeliveryTest {
    @Test
    public void burstKeepsOnlyLatestValueAndOneTask() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery delivery = new LatestIntDelivery(executor, delivered::add);

        for (int value = 0; value < 100_000; value++) delivery.offer(value);

        assertEquals(1, executor.size());
        executor.runAll();
        assertEquals(Arrays.asList(99_999), delivered);
    }

    @Test
    public void updateDuringListenerSchedulesOneFollowUp() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery[] holder = new LatestIntDelivery[1];
        holder[0] = new LatestIntDelivery(executor, value -> {
            delivered.add(value);
            if (value == 1) {
                holder[0].offer(2);
                holder[0].offer(3);
            }
        });

        holder[0].offer(1);
        executor.runNext();
        assertEquals(1, executor.size());
        executor.runAll();

        assertEquals(Arrays.asList(1, 3), delivered);
    }

    @Test
    public void rejectedScheduleCanRetryOnNextOffer() {
        RejectOnceExecutor executor = new RejectOnceExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery delivery = new LatestIntDelivery(executor, delivered::add);

        delivery.offer(1);
        delivery.offer(2);
        executor.runAll();

        assertEquals(Arrays.asList(2), delivered);
    }

    @Test
    public void offerRacingRejectedScheduleIsNotLost() {
        OfferThenRejectExecutor executor = new OfferThenRejectExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery[] holder = new LatestIntDelivery[1];
        holder[0] = new LatestIntDelivery(executor, delivered::add);
        executor.duringFirstExecute = () -> holder[0].offer(2);

        holder[0].offer(1);
        executor.runAll();

        assertEquals(Arrays.asList(2), delivered);
    }

    @Test
    public void closeDropsQueuedAndFutureValues() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery delivery = new LatestIntDelivery(executor, delivered::add);

        delivery.offer(1);
        delivery.close();
        delivery.close();
        delivery.offer(2);
        executor.runAll();

        assertTrue(delivered.isEmpty());
    }

    @Test
    public void listenerFailureDoesNotPoisonNextValue() {
        ManualExecutor executor = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        LatestIntDelivery delivery = new LatestIntDelivery(executor, value -> {
            if (value == 1) throw new IllegalStateException("boom");
            delivered.add(value);
        });

        delivery.offer(1);
        executor.runAll();
        delivery.offer(2);
        executor.runAll();

        assertEquals(Arrays.asList(2), delivered);
    }

    private static class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class RejectOnceExecutor extends ManualExecutor {
        private boolean rejected;

        @Override
        public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                throw new IllegalStateException("rejected");
            }
            super.execute(command);
        }
    }

    private static final class OfferThenRejectExecutor extends ManualExecutor {
        private Runnable duringFirstExecute;
        private boolean rejected;

        @Override
        public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                duringFirstExecute.run();
                throw new IllegalStateException("rejected");
            }
            super.execute(command);
        }
    }
}
