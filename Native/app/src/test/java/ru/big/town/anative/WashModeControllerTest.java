package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class WashModeControllerTest {
    @Test
    public void activationChecksParkingArmsThenSendsOneState() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore(events);
        FakeGateway gateway = new FakeGateway(events, new WashModeController.Gear(0, 0));
        WashModeController controller = new WashModeController(
                gateway, new WashModeRequestLease(store));

        assertEquals(WashModePolicy.Outcome.ACCEPTED, controller.activate());
        assertEquals(Arrays.asList("gear", "lease:1", "cleaning:1"), events);
        assertTrue(controller.hasArmedRequest());
    }

    @Test
    public void nonParkingFailsBeforeLeaseAndWrite() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore(events);
        FakeGateway gateway = new FakeGateway(events, new WashModeController.Gear(3, 3));
        WashModeController controller = new WashModeController(
                gateway, new WashModeRequestLease(store));

        assertEquals(WashModePolicy.Outcome.NOT_IN_PARK, controller.activate());
        assertEquals(Arrays.asList("gear"), events);
        assertFalse(controller.hasArmedRequest());
    }

    @Test
    public void missingGearFailsClosed() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore(events);
        FakeGateway gateway = new FakeGateway(events, null);
        WashModeController controller = new WashModeController(
                gateway, new WashModeRequestLease(store));

        assertEquals(WashModePolicy.Outcome.TRANSPORT_FAILURE, controller.activate());
        assertEquals(Arrays.asList("gear"), events);
        assertFalse(controller.hasArmedRequest());
    }

    @Test
    public void rejectedCleaningDisarmsTheExactGeneration() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore(events);
        FakeGateway gateway = new FakeGateway(events, new WashModeController.Gear(0, 0));
        gateway.activationWriteAccepted = false;
        WashModeController controller = new WashModeController(
                gateway, new WashModeRequestLease(store));

        assertEquals(WashModePolicy.Outcome.TRANSPORT_FAILURE, controller.activate());
        assertEquals(Arrays.asList("gear", "lease:1", "cleaning:1", "lease:0"), events);
        assertFalse(controller.hasArmedRequest());
    }

    @Test
    public void controllerDoesNotAddARepeatActivationGate() {
        List<String> events = new ArrayList<>();
        MemoryStore store = new MemoryStore(events);
        FakeGateway gateway = new FakeGateway(events, new WashModeController.Gear(0, 0));
        WashModeController controller = new WashModeController(
                gateway, new WashModeRequestLease(store));

        assertEquals(WashModePolicy.Outcome.ACCEPTED, controller.activate());
        assertEquals(WashModePolicy.Outcome.ACCEPTED, controller.activate());
        assertEquals(Arrays.asList(
                "gear", "lease:1", "cleaning:1",
                "gear", "lease:2", "cleaning:1"), events);
    }

    private static final class FakeGateway implements WashModeController.VehicleGateway {
        final List<String> events;
        final WashModeController.Gear gear;
        boolean activationWriteAccepted = true;

        FakeGateway(List<String> events, WashModeController.Gear gear) {
            this.events = events;
            this.gear = gear;
        }

        @Override
        public WashModePolicy.Outcome runActivation(WashModeController.SessionAction action) {
            return action.run(new WashModeController.Session() {
                @Override
                public WashModeController.Gear readGear() {
                    events.add("gear");
                    return gear;
                }

                @Override
                public boolean sendCleaning(int value, String label) {
                    events.add("cleaning:" + value);
                    return activationWriteAccepted;
                }
            });
        }

        @Override
        public boolean sendCleaning(int value, String label) {
            events.add("cleanup:" + value);
            return true;
        }
    }

    private static final class MemoryStore implements WashModeRequestLease.Store {
        final List<String> events;
        long next;
        long active;

        MemoryStore(List<String> events) {
            this.events = events;
        }

        @Override
        public WashModeRequestLease.Snapshot read() {
            return new WashModeRequestLease.Snapshot(next, active);
        }

        @Override
        public boolean write(long nextGeneration, long activeGeneration) {
            next = nextGeneration;
            active = activeGeneration;
            events.add("lease:" + activeGeneration);
            return true;
        }
    }
}
