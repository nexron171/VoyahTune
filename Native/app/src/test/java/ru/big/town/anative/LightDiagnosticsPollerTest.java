package ru.big.town.anative;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Test;

public class LightDiagnosticsPollerTest {
    private static final int U = LightDiagnosticsPoller.UNKNOWN;

    @Test public void refreshesKnownValuesEverySecondWithoutCanEvents() {
        Fixture f = new Fixture();
        f.poller.start();
        f.complete(0, 100, 200, 300);
        f.clock.advance(999);
        assertEquals(1, f.reads);
        f.clock.advance(1);
        assertEquals(2, f.reads);
        f.complete(2, 110, 220, 330);
        assertArrayEquals(new int[]{2, 110, 220, 330}, f.latest());
        f.clock.advance(1_000);
        assertEquals(3, f.reads);
    }

    @Test public void newerEventWinsOnlyForItsOwnField() {
        Fixture f = new Fixture();
        f.poller.start();
        f.complete(0, 100, 200, 300);
        f.clock.advance(1_000);
        // Even a repeated event confirms fresher data than the pending snapshot.
        f.poller.onEvent(1, 100);
        f.poller.onEvent(2, 250);
        f.complete(2, 90, 190, 330);
        assertArrayEquals(new int[]{2, 100, 250, 330}, f.latest());
        f.clock.advance(1_000);
        f.complete(2, 120, 260, 340);
        assertArrayEquals(new int[]{2, 120, 260, 340}, f.latest());
    }

    @Test public void slowReadNeverAccumulatesMoreRequests() {
        Fixture f = new Fixture();
        f.poller.start();
        f.clock.advance(60_000);
        assertEquals(1, f.reads);
        assertEquals(1, f.pending.size());
        assertEquals(1, f.clock.tasks.size());
        f.complete(0, 100, 200, 300);
        f.clock.advance(1_000);
        assertEquals(2, f.reads);
    }

    @Test public void stopCancelsTimerAndDiscardsLateReadAndEvents() {
        Fixture f = new Fixture();
        f.poller.start();
        f.poller.stop();
        int deliveries = f.delivered.size();
        f.complete(0, 100, 200, 300);
        f.poller.onEvent(1, 500);
        f.clock.advance(10_000);
        assertEquals(1, f.reads);
        assertEquals(deliveries, f.delivered.size());
        assertArrayEquals(new int[]{U, U, U, U}, f.latest());
        assertTrue(f.clock.tasks.isEmpty());
    }

    @Test public void reconnectRejectsOldSnapshotWithoutOverlappingReads() {
        Fixture f = new Fixture();
        f.poller.start();
        f.poller.stop();
        f.poller.start();
        f.poller.onEvent(1, 500);
        f.clock.advance(1_000);
        assertEquals(1, f.reads);
        f.complete(0, 100, 200, 300);
        assertArrayEquals(new int[]{U, 500, U, U}, f.latest());
        f.clock.advance(1_000);
        assertEquals(2, f.reads);
        f.complete(2, 510, 520, 530);
        assertArrayEquals(new int[]{2, 510, 520, 530}, f.latest());
        assertEquals(1, f.clock.tasks.size());
    }

    @Test public void failedReadClearsOldValuesPreservesNewEventsAndRetries() {
        Fixture f = new Fixture();
        f.poller.start();
        f.complete(0, 100, 200, 300);
        f.clock.advance(1_000);
        f.poller.onEvent(3, 350);
        f.pending.removeFirst().accept(null);
        assertArrayEquals(new int[]{U, U, U, 350}, f.latest());
        f.clock.advance(1_000);
        f.complete(2, 110, 220, 360);
        assertArrayEquals(new int[]{2, 110, 220, 360}, f.latest());
    }

    @Test public void rejectedReadCanCompleteSynchronouslyAndRetry() {
        Clock clock = new Clock();
        int[] reads = {0};
        LightDiagnosticsPoller poller = new LightDiagnosticsPoller(clock, done -> {
            ++reads[0];
            done.accept(null);
        }, ignored -> { });
        poller.start();
        clock.advance(2_000);
        assertEquals(3, reads[0]);
        assertEquals(1, clock.tasks.size());
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final ArrayDeque<Consumer<int[]>> pending = new ArrayDeque<>();
        final List<int[]> delivered = new ArrayList<>();
        int reads;
        final LightDiagnosticsPoller poller = new LightDiagnosticsPoller(clock, done -> {
            ++reads;
            pending.addLast(done);
        }, delivered::add);

        void complete(int... values) { pending.removeFirst().accept(values); }
        int[] latest() { return delivered.get(delivered.size() - 1); }
    }

    private static final class Clock implements LightDiagnosticsPoller.Scheduler {
        final ArrayList<Scheduled> tasks = new ArrayList<>();
        long now;

        @Override public void postDelayed(Runnable task, long delayMs) {
            tasks.add(new Scheduled(task, now + delayMs));
        }

        @Override public void removeCallbacks(Runnable task) {
            tasks.removeIf(scheduled -> scheduled.task == task);
        }

        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Scheduled next = null;
                for (Scheduled scheduled : tasks) {
                    if (scheduled.at <= target && (next == null || scheduled.at < next.at)) {
                        next = scheduled;
                    }
                }
                if (next == null) break;
                tasks.remove(next);
                now = next.at;
                next.task.run();
            }
            now = target;
        }
    }

    private static final class Scheduled {
        final Runnable task;
        final long at;
        Scheduled(Runnable task, long at) { this.task = task; this.at = at; }
    }
}
