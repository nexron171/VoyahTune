package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DoorPauseWorkGateTest {
    @Test
    public void duplicateIsDroppedUntilRunFinishes() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();

        int first = gate.tryAcquire();
        assertTrue(first > 0);
        assertTrue(gate.isBusy());
        for (int i = 0; i < 100_000; i++) {
            assertTrue(gate.tryAcquire() == DoorPauseWorkGate.REJECTED_GENERATION);
        }

        gate.release(first);
        int second = gate.tryAcquire();
        assertTrue(second > first);
    }

    @Test
    public void closeRejectsQueuedAndFutureRuns() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int generation = gate.tryAcquire();

        gate.close();

        assertFalse(gate.isBusy());
        assertFalse(gate.isLatest(generation));
        assertTrue(gate.tryAcquire() == DoorPauseWorkGate.REJECTED_GENERATION);
        gate.release(generation);
        assertTrue(gate.tryAcquire() == DoorPauseWorkGate.REJECTED_GENERATION);
    }

    @Test
    public void staleReleaseCannotOpenNewRun() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int first = gate.tryAcquire();
        gate.release(first);
        int second = gate.tryAcquire();

        gate.release(first);

        assertTrue(gate.isBusy());
        assertTrue(gate.isLatest(second));
        assertTrue(gate.tryAcquire() == DoorPauseWorkGate.REJECTED_GENERATION);
    }

    @Test
    public void releasedRunStaysLatestUntilSuperseded() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int first = gate.tryAcquire();
        gate.release(first);

        assertTrue(gate.isLatest(first));

        int second = gate.tryAcquire();
        assertFalse(gate.isLatest(first));
        assertTrue(gate.isLatest(second));
    }

    @Test
    public void claimedFallbackBlocksNewRunUntilDispatchFinishes() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int first = gate.tryAcquire();
        gate.release(first);

        assertTrue(gate.tryClaimFallback(first));
        assertTrue(gate.tryAcquire() == DoorPauseWorkGate.REJECTED_GENERATION);

        gate.finishFallback(first);
        assertTrue(gate.tryAcquire() > first);
    }

    @Test
    public void proxyResultCanBeConsumedOnlyOnce() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int generation = gate.tryAcquire();

        assertTrue(gate.tryClaimFallback(generation));
        gate.finishFallback(generation);
        assertFalse(gate.tryClaimFallback(generation));
    }

    @Test
    public void acknowledgedProxyCannotAlsoFallback() {
        DoorPauseWorkGate gate = new DoorPauseWorkGate();
        int generation = gate.tryAcquire();

        gate.acknowledgeProxy(generation);

        assertFalse(gate.tryClaimFallback(generation));
    }
}
