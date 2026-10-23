package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ClusterNavigationLeaseTest {
    @Test
    public void leaseExpiresWhenCarPlayStopsSending() {
        ClusterNavigationLease state = new ClusterNavigationLease();
        state.update(true, 7, 150, "Road", 1000, 1000);
        assertNotNull(state.current(5999));
        assertNull(state.current(6000));
    }

    @Test
    public void stoppedInvalidDelayedOrFutureMessagesNeverReviveGuidance() {
        ClusterNavigationLease state = new ClusterNavigationLease();
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
        ClusterNavigationLease state = new ClusterNavigationLease();
        state.update(true, 3, 20, "A\0B", 1000, 1000);
        assertEquals("A B", state.current(1000).road);
        ClusterNavigationLease.Maneuver previous = state.current(1000);
        state.update(true, 3, 20, "A\0B", 5000, 5000);
        assertEquals(previous, state.current(8000));
        state.clear();
        assertNull(state.current(8000));
    }

    @Test
    public void disablingClearsTheTurnAndIgnoresHeartbeatsUntilEnabled() {
        ClusterNavigationLease state = new ClusterNavigationLease();
        state.update(true, 7, 150, "Road", 1000, 1000);
        state.setEnabled(false);
        assertNull(state.current(1000));

        state.update(true, 3, 100, "Next road", 1100, 1100);
        assertNull(state.current(1100));
        state.setEnabled(true);
        assertNull(state.current(1100));

        state.update(true, 3, 90, "Next road", 1200, 1200);
        assertEquals(90, state.current(1200).distance);
    }

    @Test
    public void disabledStartupCannotDisplayTheFirstSnapshot() {
        ClusterNavigationLease state = new ClusterNavigationLease();
        state.setEnabled(false);
        state.update(true, 7, 150, "Road", 1000, 1000);
        assertNull(state.current(1000));
    }
}
