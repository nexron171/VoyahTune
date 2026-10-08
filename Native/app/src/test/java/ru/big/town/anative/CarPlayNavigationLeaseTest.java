package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class CarPlayNavigationLeaseTest {
    @Test
    public void leaseExpiresWhenCarPlayStopsSending() {
        CarPlayNavigationLease state = new CarPlayNavigationLease();
        state.update(true, 7, 150, "Road", 1000, 1000);
        assertNotNull(state.current(5999));
        assertNull(state.current(6000));
    }

    @Test
    public void stoppedInvalidDelayedOrFutureMessagesNeverReviveGuidance() {
        CarPlayNavigationLease state = new CarPlayNavigationLease();
        state.update(true, 7, 150, "Road", 1000, 1000);
        state.update(false, 0, 0, "", 1100, 1100);
        assertNull(state.current(1100));
        state.update(true, 7, 150, "Road", 1000, 7000);
        assertNull(state.current(7000));
        state.update(true, 7, 150, "Road", 8000, 7000);
        assertNull(state.current(7000));
        state.update(true, 100, 150, "Road", 7000, 7000);
        assertNull(state.current(7000));
    }

    @Test
    public void heartbeatRefreshesLeaseAndClearDropsOwnershipData() {
        CarPlayNavigationLease state = new CarPlayNavigationLease();
        state.update(true, 3, 20, "A\0B", 1000, 1000);
        assertEquals("A B", state.current(1000).road);
        CarPlayNavigationLease.Maneuver previous = state.current(1000);
        state.update(true, 3, 20, "A\0B", 5000, 5000);
        assertEquals(previous, state.current(8000));
        state.clear();
        assertNull(state.current(8000));
    }
}
