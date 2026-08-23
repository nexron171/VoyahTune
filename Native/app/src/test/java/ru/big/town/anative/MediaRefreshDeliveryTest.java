package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class MediaRefreshDeliveryTest {
    @Test
    public void callbackStormCreatesOneHeavyTask() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        MediaRefreshDelivery delivery = new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(work + ":" + reason));

        for (int i = 0; i < 100_000; i++) {
            delivery.offer(MediaRefreshDelivery.Work.PUBLISH, "p" + i);
        }

        assertEquals(1, executor.tasks.size());
        executor.runAll();
        assertEquals(Arrays.asList("PUBLISH:p99999"), delivered);
    }

    @Test
    public void weakerRequestCannotReplacePendingRebuild() {
        ManualExecutor executor = new ManualExecutor();
        List<MediaRefreshDelivery.Work> delivered = new ArrayList<>();
        MediaRefreshDelivery delivery = new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(work));

        delivery.offer(MediaRefreshDelivery.Work.REPICK, "playback");
        delivery.offer(MediaRefreshDelivery.Work.REBUILD, "sessions");
        delivery.offer(MediaRefreshDelivery.Work.PUBLISH, "request");
        executor.runAll();

        assertEquals(Arrays.asList(MediaRefreshDelivery.Work.REBUILD), delivered);
    }

    @Test
    public void requestCannotOvertakePendingRepick() {
        ManualExecutor executor = new ManualExecutor();
        List<MediaRefreshDelivery.Work> delivered = new ArrayList<>();
        MediaRefreshDelivery delivery = new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(work));

        delivery.offer(MediaRefreshDelivery.Work.REPICK, "playback");
        delivery.offer(MediaRefreshDelivery.Work.PUBLISH, "request");
        executor.runAll();

        assertEquals(Arrays.asList(MediaRefreshDelivery.Work.REPICK), delivered);
    }

    @Test
    public void updatesDuringRefreshCreateOneFollowUp() {
        ManualExecutor executor = new ManualExecutor();
        List<MediaRefreshDelivery.Work> delivered = new ArrayList<>();
        AtomicReference<MediaRefreshDelivery> holder = new AtomicReference<>();
        holder.set(new MediaRefreshDelivery(executor, (work, reason) -> {
            delivered.add(work);
            if (delivered.size() == 1) {
                holder.get().offer(MediaRefreshDelivery.Work.PUBLISH, "metadata");
                holder.get().offer(MediaRefreshDelivery.Work.REBUILD, "sessions");
                holder.get().offer(MediaRefreshDelivery.Work.REPICK, "playback");
            }
        }));

        holder.get().offer(MediaRefreshDelivery.Work.REPICK, "first");
        executor.runAll();

        assertEquals(Arrays.asList(
                MediaRefreshDelivery.Work.REPICK,
                MediaRefreshDelivery.Work.REBUILD), delivered);
    }

    @Test
    public void rejectedScheduleCanRetryOnNextOffer() {
        RejectOnceExecutor executor = new RejectOnceExecutor();
        List<String> delivered = new ArrayList<>();
        MediaRefreshDelivery delivery = new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(reason));

        delivery.offer(MediaRefreshDelivery.Work.PUBLISH, "rejected");
        delivery.offer(MediaRefreshDelivery.Work.PUBLISH, "latest");
        executor.runAll();

        assertEquals(Arrays.asList("latest"), delivered);
    }

    @Test
    public void offerDuringRejectedScheduleIsNotLost() {
        OfferThenRejectExecutor executor = new OfferThenRejectExecutor();
        List<String> delivered = new ArrayList<>();
        AtomicReference<MediaRefreshDelivery> holder = new AtomicReference<>();
        holder.set(new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(reason)));
        executor.duringFirstExecute = () -> holder.get().offer(
                MediaRefreshDelivery.Work.REPICK, "latest");

        holder.get().offer(MediaRefreshDelivery.Work.PUBLISH, "first");
        executor.runAll();

        assertEquals(Arrays.asList("latest"), delivered);
    }

    @Test
    public void closeDropsQueuedAndFutureWork() {
        ManualExecutor executor = new ManualExecutor();
        List<String> delivered = new ArrayList<>();
        MediaRefreshDelivery delivery = new MediaRefreshDelivery(executor,
                (work, reason) -> delivered.add(reason));

        delivery.offer(MediaRefreshDelivery.Work.REBUILD, "queued");
        delivery.close();
        delivery.offer(MediaRefreshDelivery.Work.REBUILD, "future");
        executor.runAll();

        assertEquals(0, delivered.size());
    }

    private static class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) { tasks.addLast(command); }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class RejectOnceExecutor extends ManualExecutor {
        private boolean rejected;

        @Override public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                throw new RejectedExecutionException("first");
            }
            super.execute(command);
        }
    }

    private static final class OfferThenRejectExecutor extends ManualExecutor {
        private Runnable duringFirstExecute;
        private boolean rejected;

        @Override public void execute(Runnable command) {
            if (!rejected) {
                rejected = true;
                duringFirstExecute.run();
                throw new RejectedExecutionException("first");
            }
            super.execute(command);
        }
    }
}
