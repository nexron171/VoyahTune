package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryHeatRefreshGateTest {
    @Test
    public void burstKeepsOneRunningAndOneMergedFollowUp() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, false, "first");
        for (int i = 0; i < 100_000; i++) {
            assertNull(gate.offer(1L, false, "v" + i));
        }

        BatteryHeatRefreshGate.Completion completion = gate.finish(first);

        assertFalse(completion.publish);
        assertTrue(gate.finish(completion.next).publish);
    }

    @Test
    public void autoEvaluationIsNotLostBehindNewerPublishOnlyWork() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, true, "temperature");
        gate.offer(1L, false, "vehicle-state");

        BatteryHeatRefreshGate.Request next = gate.finish(first).next;

        assertTrue(next.evaluateAuto);
        assertTrue("temperature".equals(next.reason));
    }

    @Test
    public void latestAutoReasonWins() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, true, "temperature");
        gate.offer(1L, true, "poll");

        BatteryHeatRefreshGate.Request next = gate.finish(first).next;

        assertTrue(next.evaluateAuto);
        assertTrue("poll".equals(next.reason));
    }

    @Test
    public void rejectedWorkAndNewerIntentAreRetriedTogether() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, true, "temperature");
        gate.offer(1L, false, "request");

        gate.reject(first);
        BatteryHeatRefreshGate.Request retry = gate.retry();

        assertTrue(retry.evaluateAuto);
        assertTrue("temperature".equals(retry.reason));
        assertTrue(gate.finish(retry).publish);
    }

    @Test
    public void newOfferCanRestartBeforeDelayedRetry() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, false, "first");
        gate.reject(first);

        BatteryHeatRefreshGate.Request restarted = gate.offer(1L, true, "temperature");

        assertTrue(restarted.evaluateAuto);
        assertNull(gate.retry());
        assertTrue(gate.finish(restarted).publish);
    }

    @Test
    public void staleCompletionCannotDisruptCurrentRun() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request running = gate.offer(1L, false, "running");

        assertFalse(gate.finish(new BatteryHeatRefreshGate().offer(
                1L, false, "stale")).publish);
        assertTrue(gate.finish(running).publish);
    }

    @Test
    public void closeDropsRunningPendingAndFutureWork() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, false, "first");
        gate.offer(1L, true, "pending");
        gate.close();

        assertFalse(gate.finish(first).publish);
        assertNull(gate.retry());
        assertNull(gate.offer(1L, false, "future"));
    }

    @Test
    public void newerEpochOwnsMergedFollowUp() {
        BatteryHeatRefreshGate gate = new BatteryHeatRefreshGate();
        BatteryHeatRefreshGate.Request first = gate.offer(1L, true, "old");
        gate.offer(2L, false, "new");

        BatteryHeatRefreshGate.Request next = gate.finish(first).next;

        assertTrue(next.epoch == 2L);
        assertFalse(next.evaluateAuto);
        assertTrue("new".equals(next.reason));
    }
}
