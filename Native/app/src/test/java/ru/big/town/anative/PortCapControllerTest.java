package ru.big.town.anative;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class PortCapControllerTest {
    private static final class Session implements OemVehicleStateTransport.Session {
        OemVehicleStateTransport.GearStatus gear = new OemVehicleStateTransport.GearStatus(0, 0);
        OemVehicleStateTransport.Result result = OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
        final List<OemVehicleStateTransport.StateValue> writes = new ArrayList<>();
        boolean read, fuelRead, failFuelRead;
        OemVehicleStateTransport.FuelLevel fuel = new OemVehicleStateTransport.FuelLevel(52, 25);

        @Override public OemVehicleStateTransport.GearStatus readGearStatus() { read = true; return gear; }
        @Override public OemVehicleStateTransport.FuelLevel readFuelLevel() {
            assertEquals("Read fuel only after the opening request", 1, writes.size());
            fuelRead = true;
            if (failFuelRead) throw new IllegalStateException("Fuel unavailable");
            return fuel;
        }
        @Override public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
            throw new AssertionError("Unexpected state read");
        }
        @Override public OemVehicleStateTransport.Result sendVehicleState(
                OemVehicleStateTransport.StateValue state, String label) {
            assertTrue("Gear must be checked before opening", read);
            writes.add(state);
            return result;
        }
        @Override public OemVehicleStateTransport.Result sendBundle(
                Map<OemVehicleStateTransport.StateKey, Integer> values, String label) {
            throw new AssertionError("Cap opening must send only one TX58");
        }
    }

    @Test public void parkingOpensOnlyTheRequestedCap() {
        for (String type : new String[]{"fuel", "charge"}) {
            Session session = new Session();
            PortCapController.OpenResult opened = PortCapController.open(session, "port_cap:" + type);
            assertEquals(PortCapController.Outcome.ACCEPTED, opened.outcome);
            assertEquals(type.equals("fuel"), session.fuelRead);
            assertEquals(type.equals("fuel") ? Integer.valueOf(39) : null, opened.refillLiters);
            assertEquals(1, session.writes.size());
            OemVehicleStateTransport.StateValue write = session.writes.get(0);
            assertEquals(type.equals("fuel") ? "IVI_FUEL_PORT_CAP" : "IVI_CHRG_PORT_CAP", write.key.name);
            assertEquals(type.equals("fuel") ? 778 : 779, write.key.stableId);
            assertEquals(1, write.value);
            assertNotEquals("CHARGE_GUN_UNLOCK_SET", write.key.name);
        }
    }

    @Test public void nonParkingAndInconsistentGearNeverSend() {
        for (String action : new String[]{"port_cap:fuel", "port_cap:charge"}) {
            for (int[] gear : new int[][]{{1,1}, {2,2}, {3,3}, {5,-1}, {0,-1}, {5,0}}) {
                Session session = new Session();
                session.gear = new OemVehicleStateTransport.GearStatus(gear[0], gear[1]);
                assertEquals(PortCapController.Outcome.NOT_IN_PARK, PortCapController.open(session, action).outcome);
                assertTrue(session.writes.isEmpty());
                assertFalse(session.fuelRead);
            }
        }
    }

    @Test public void missingGearAndFailedWriteAreNotReportedAsSuccess() {
        Session unavailable = new Session();
        unavailable.gear = null;
        assertEquals(PortCapController.Outcome.STATE_UNAVAILABLE, PortCapController.open(unavailable, "port_cap:fuel").outcome);
        assertTrue(unavailable.writes.isEmpty());
        Session failure = new Session();
        failure.result = OemVehicleStateTransport.Result.TRANSIENT_FAILURE;
        assertEquals(PortCapController.Outcome.TRANSPORT_FAILURE, PortCapController.open(failure, "port_cap:fuel").outcome);
        assertEquals(1, failure.writes.size());
        assertFalse(failure.fuelRead);
    }

    @Test public void unknownTargetNeverSends() {
        Session session = new Session();
        assertEquals(PortCapController.Outcome.TRANSPORT_FAILURE, PortCapController.open(session, "port_cap:unlock").outcome);
        assertFalse(session.read);
        assertTrue(session.writes.isEmpty());
    }

    @Test public void fuelReadFailureDoesNotChangeAlreadyAcceptedOpeningIntoAnError() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Session session = new Session();
            if (scenario == 0) session.fuel = null;
            if (scenario == 1) session.fuel = new OemVehicleStateTransport.FuelLevel(52, -9999);
            if (scenario == 2) session.failFuelRead = true;
            PortCapController.OpenResult opened = PortCapController.open(session, "port_cap:fuel");
            assertEquals(PortCapController.Outcome.ACCEPTED, opened.outcome);
            assertNull(opened.refillLiters);
            assertEquals(1, session.writes.size());
        }
    }
}
