package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LatestSingleFlightTest {
    @Test
    public void requestWhileRunningProducesOneFollowUp() {
        LatestSingleFlight gate = new LatestSingleFlight();
        gate.request();
        assertTrue(gate.tryStart());

        for (int i = 0; i < 100_000; i++) gate.request();
        assertFalse(gate.tryStart());

        gate.complete();
        assertTrue(gate.tryStart());
        gate.complete();
        assertFalse(gate.tryStart());
    }

    @Test(expected = IllegalStateException.class)
    public void completingIdleGateIsRejected() {
        new LatestSingleFlight().complete();
    }
}
