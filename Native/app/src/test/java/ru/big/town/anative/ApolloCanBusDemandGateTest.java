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
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                gate.acquire(1L, null));
        assertFalse(gate.beginQuery(1L, null));
        assertFalse(gate.release(1L, null));
        assertEquals(1, gate.armIdleRelease(false));
    }

    @Test
    public void duplicateAcquireIsIdempotentWithinActiveSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, owner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_EXISTING, gate.acquire(10L, owner));
        assertTrue(gate.isActive());
        assertTrue(gate.isActive(10L, owner));
    }

    @Test
    public void sameSessionWithDifferentOwnerIsRejected() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, owner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                gate.acquire(10L, new Object()));
        assertFalse(gate.release(10L, new Object()));
        assertTrue(gate.isActive(10L, owner));
    }

    @Test
    public void staleReleaseCannotClearNewerVisibleSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object oldOwner = new Object();
        Object currentOwner = new Object();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, oldOwner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(20L, currentOwner));

        assertFalse(gate.release(10L, oldOwner));

        assertTrue(gate.isActive(20L, currentOwner));
    }

    @Test
    public void releaseBeforeAcquireTombstonesSameSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();
        Object successorOwner = new Object();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, owner));

        assertTrue(gate.release(20L, successorOwner));

        assertFalse(gate.isActive());
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                gate.acquire(20L, successorOwner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                gate.acquire(10L, owner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(21L, successorOwner));
    }

    @Test
    public void onlyExactCurrentOwnerDeathReleasesDemand() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object oldOwner = new Object();
        Object currentOwner = new Object();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(10L, oldOwner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(20L, currentOwner));

        assertFalse(gate.ownerDied(10L, oldOwner));
        assertFalse(gate.ownerDied(20L, oldOwner));
        assertTrue(gate.isActive(20L, currentOwner));
        assertTrue(gate.ownerDied(20L, currentOwner));
        assertFalse(gate.isActive());
    }

    @Test
    public void staleDeathCannotClearNewSessionUsingSameOwner() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object processOwner = new Object();
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(10L, processOwner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(20L, processOwner));

        assertFalse(gate.ownerDied(10L, processOwner));
        assertTrue(gate.isActive(20L, processOwner));
    }

    @Test
    public void duplicateQueryInSameSessionDoesNotCreateTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, owner));
        assertTrue(gate.beginQuery(10L, owner));
        assertFalse(gate.beginQuery(10L, owner));

        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(10L));
    }

    @Test
    public void newerSessionCreatesExactlyOneTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object firstOwner = new Object();
        Object secondOwner = new Object();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(10L, firstOwner));
        assertTrue(gate.beginQuery(10L, firstOwner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(20L, secondOwner));
        assertFalse(gate.beginQuery(20L, secondOwner));
        assertFalse(gate.beginQuery(20L, secondOwner));

        assertEquals(20L, gate.finishQuery(10L));
        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(20L));
    }

    @Test
    public void staleQueryDoesNotCreateTrailingRefresh() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object oldOwner = new Object();
        Object currentOwner = new Object();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW,
                gate.acquire(20L, currentOwner));
        assertTrue(gate.beginQuery(20L, currentOwner));
        assertFalse(gate.beginQuery(10L, oldOwner));

        assertEquals(ApolloCanBusDemandGate.NO_QUERY_SESSION,
                gate.finishQuery(20L));
    }

    @Test
    public void pendingWriteDefersReleaseUntilItFinishes() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();
        gate.acquire(10L, owner);
        gate.release(10L, owner);

        assertEquals(ApolloCanBusDemandGate.REJECTED_GENERATION,
                gate.armIdleRelease(true));
        int idle = gate.armIdleRelease(false);
        assertFalse(gate.isIdleReleaseCurrent(idle, true));
        assertTrue(gate.isIdleReleaseCurrent(idle, false));
    }

    @Test
    public void reacquireRejectsAlreadyDequeuedIdleCallback() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();
        gate.acquire(10L, owner);
        gate.release(10L, owner);
        int idle = gate.armIdleRelease(false);

        gate.acquire(20L, new Object());

        assertFalse(gate.isIdleReleaseCurrent(idle, false));
        assertTrue(gate.isActive());
    }

    @Test
    public void closeRejectsDemandAndQueuedIdleCallbacks() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        gate.release(10L, new Object());
        int idle = gate.armIdleRelease(false);

        gate.close();

        assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                gate.acquire(20L, new Object()));
        assertFalse(gate.isActive());
        assertFalse(gate.isIdleReleaseCurrent(idle, false));
        assertEquals(ApolloCanBusDemandGate.REJECTED_GENERATION,
                gate.armIdleRelease(false));
    }

    @Test
    public void staleReleaseStressNeverClearsCurrentSession() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();
        for (long session = 1L; session <= 100_000L; session++) {
            assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(session, owner));
            assertFalse(gate.release(session - 1L, owner));
            assertTrue(gate.isActive(session, owner));
            assertTrue(gate.release(session, owner));
            assertEquals(ApolloCanBusDemandGate.ACQUIRE_REJECTED,
                    gate.acquire(session, owner));
        }
    }

    @Test
    public void queryRequiresTheActiveOwnerTuple() {
        ApolloCanBusDemandGate gate = new ApolloCanBusDemandGate();
        Object owner = new Object();

        assertFalse(gate.beginQuery(10L, owner));
        assertEquals(ApolloCanBusDemandGate.ACQUIRE_NEW, gate.acquire(10L, owner));
        assertFalse(gate.beginQuery(10L, new Object()));
        assertTrue(gate.beginQuery(10L, owner));
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
