package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloCanBusDemandGateTest {
    @Test
    public void bootHasNoTransportDemand() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();

        assertFalse(gate.isActive());
        assertEquals(1, gate.armIdleRelease(false));
    }

    @Test
    public void duplicateAcquireIsIdempotentWithinActiveSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_EXISTING, gate.acquire(10L));
        assertTrue(gate.isActive());
        assertTrue(gate.isActive(10L));
    }

    @Test
    public void staleReleaseCannotClearNewerVisibleSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(20L));

        assertFalse(gate.release(10L));

        assertTrue(gate.isActive(20L));
    }

    @Test
    public void releaseBeforeAcquireTombstonesSameSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L));

        assertTrue(gate.release(20L));

        assertFalse(gate.isActive());
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED, gate.acquire(20L));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED, gate.acquire(10L));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(21L));
    }

    @Test
    public void duplicateQueryInSameSessionDoesNotCreateTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();

        assertTrue(gate.beginQuery(10L));
        assertFalse(gate.beginQuery(10L));

        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(10L));
    }

    @Test
    public void newerSessionCreatesExactlyOneTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();

        assertTrue(gate.beginQuery(10L));
        assertFalse(gate.beginQuery(20L));
        assertFalse(gate.beginQuery(20L));

        assertEquals(20L, gate.finishQuery(10L));
        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(20L));
    }

    @Test
    public void staleQueryDoesNotCreateTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();

        assertTrue(gate.beginQuery(20L));
        assertFalse(gate.beginQuery(10L));

        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(20L));
    }

    @Test
    public void pendingWriteDefersReleaseUntilItFinishes() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        gate.acquire(10L);
        gate.release(10L);

        assertEquals(ApolloCanBusDemandGate.REJECTED_GENERATION,
                gate.armIdleRelease(true));
        int idle = gate.armIdleRelease(false);
        assertFalse(gate.isIdleReleaseCurrent(idle, true));
        assertTrue(gate.isIdleReleaseCurrent(idle, false));
    }

    @Test
    public void reacquireRejectsAlreadyDequeuedIdleCallback() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        gate.acquire(10L);
        gate.release(10L);
        int idle = gate.armIdleRelease(false);

        gate.acquire(20L);

        assertFalse(gate.isIdleReleaseCurrent(idle, false));
        assertTrue(gate.isActive());
    }

    @Test
    public void closeRejectsDemandAndQueuedIdleCallbacks() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        gate.release(10L);
        int idle = gate.armIdleRelease(false);

        gate.close();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED, gate.acquire(20L));
        assertFalse(gate.isActive());
        assertFalse(gate.isIdleReleaseCurrent(idle, false));
        assertEquals(ApolloCanBusDemandGate.REJECTED_GENERATION,
                gate.armIdleRelease(false));
    }

    @Test
    public void staleReleaseStressNeverClearsCurrentSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        for (long session = 1L; session <= 100_000L; session++) {
            assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(session));
            assertFalse(gate.release(session - 1L));
            assertTrue(gate.isActive(session));
            assertTrue(gate.release(session));
            assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                    gate.acquire(session));
        }
    }

    @Test
    public void reconnectBackoffRemainsFiveToSixtySeconds() {
        long[] expected = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L};
        for (int attempt = 0; attempt < expected.length; attempt++) {
            assertEquals(expected[attempt], ApolloCanBusDemandGate.reconnectDelayMs(
                    attempt, 5_000L, 60_000L));
        }
    }
}
