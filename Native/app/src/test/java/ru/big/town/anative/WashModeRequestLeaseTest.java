package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class WashModeRequestLeaseTest {
    @Test
    public void restoresArmedGenerationAndFencesStaleCleanup() {
        MemoryStore store = new MemoryStore();
        WashModeRequestLease first = new WashModeRequestLease(store);
        long firstGeneration = first.arm();
        long secondGeneration = first.arm();

        WashModeRequestLease restored = new WashModeRequestLease(store);
        assertEquals(secondGeneration, restored.activeGeneration());
        assertFalse(restored.disarm(firstGeneration));
        assertEquals(secondGeneration, restored.activeGeneration());
        assertTrue(restored.disarm(secondGeneration));
        assertEquals(0L, restored.activeGeneration());
    }

    @Test
    public void failedPersistenceDoesNotPublishArmOrDisarm() {
        MemoryStore store = new MemoryStore();
        store.acceptWrites = false;
        WashModeRequestLease lease = new WashModeRequestLease(store);

        assertEquals(0L, lease.arm());
        assertEquals(0L, lease.activeGeneration());

        store.acceptWrites = true;
        long generation = lease.arm();
        store.acceptWrites = false;
        assertFalse(lease.disarm(generation));
        assertEquals(generation, lease.activeGeneration());
    }

    @Test
    public void cleanupFailureWaitsForAnotherExplicitLifecycleCall() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        WashModeRequestLease lease = new WashModeRequestLease(store);
        lease.arm();
        CleanupGateway gateway = new CleanupGateway(events);
        WashModeController controller = new WashModeController(gateway, lease);

        gateway.acceptCleanup = false;
        assertFalse(controller.cleanupRequestBit("sleep"));
        assertTrue(controller.hasArmedRequest());
        assertEquals(1, events.size());

        gateway.acceptCleanup = true;
        assertTrue(controller.cleanupRequestBit("wake"));
        assertFalse(controller.hasArmedRequest());
        assertEquals(2, events.size());
    }

    private static final class CleanupGateway implements WashModeController.VehicleGateway {
        final List<String> events;
        boolean acceptCleanup;

        CleanupGateway(List<String> events) {
            this.events = events;
        }

        @Override
        public WashModePolicy.Outcome runActivation(WashModeController.SessionAction action) {
            throw new AssertionError("activation is not expected");
        }

        @Override
        public boolean sendCleaning(int value, String label) {
            events.add(label + ":" + value);
            return acceptCleanup;
        }
    }

    private static final class MemoryStore implements WashModeRequestLease.Store {
        long next;
        long active;
        boolean acceptWrites = true;

        @Override
        public WashModeRequestLease.Snapshot read() {
            return new WashModeRequestLease.Snapshot(next, active);
        }

        @Override
        public boolean write(long nextGeneration, long activeGeneration) {
            if (!acceptWrites) return false;
            next = nextGeneration;
            active = activeGeneration;
            return true;
        }
    }
}
