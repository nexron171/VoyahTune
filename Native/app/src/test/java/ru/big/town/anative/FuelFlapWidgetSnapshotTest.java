package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Map;

public class FuelFlapWidgetSnapshotTest {
    private static final class Session implements OemVehicleStateTransport.Session {
        OemVehicleStateTransport.GearStatus gear = new OemVehicleStateTransport.GearStatus(0, 0);
        OemVehicleStateTransport.FuelLevel fuel = new OemVehicleStateTransport.FuelLevel(52, 25);
        boolean failFuel;

        @Override
        public OemVehicleStateTransport.GearStatus readGearStatus() {
            return gear;
        }

        @Override
        public OemVehicleStateTransport.FuelLevel readFuelLevel() {
            if (failFuel) throw new IllegalStateException("Fuel unavailable");
            return fuel;
        }

        @Override
        public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
            throw new AssertionError("Unexpected read");
        }

        @Override
        public OemVehicleStateTransport.Result sendVehicleState(
                OemVehicleStateTransport.StateValue value, String label) {
            throw new AssertionError("Reading the card must never open the flap");
        }

        @Override
        public OemVehicleStateTransport.Result sendBundle(
                Map<OemVehicleStateTransport.StateKey, Integer> values, String label) {
            throw new AssertionError("Reading the card must never send commands");
        }
    }

    @Test
    public void readsAvailableVolumeWithoutOpeningFlap() {
        Session session = new Session();
        FuelFlapWidgetSnapshot state = FuelFlapWidgetSnapshot.read(session);
        assertTrue(state.canOpen);
        assertEquals(52, state.fuel.capacityLiters);
        assertEquals(25f, state.fuel.remainingPercent, 0f);
        assertEquals(Integer.valueOf(39), state.refillLiters);
        session.fuel = new OemVehicleStateTransport.FuelLevel(60, 50);
        assertEquals(Integer.valueOf(30), FuelFlapWidgetSnapshot.read(session).refillLiters);
    }

    @Test
    public void missingAndNonParkingGearBlockOpeningButKeepFuelVisible() {
        Session session = new Session();
        for (OemVehicleStateTransport.GearStatus gear :
                new OemVehicleStateTransport.GearStatus[] {
                    null,
                    new OemVehicleStateTransport.GearStatus(1, 1),
                    new OemVehicleStateTransport.GearStatus(0, -1)
                }) {
            session.gear = gear;
            FuelFlapWidgetSnapshot state = FuelFlapWidgetSnapshot.read(session);
            assertFalse(state.canOpen);
            assertEquals(Integer.valueOf(39), state.refillLiters);
            assertFalse(state.message.isEmpty());
        }
    }

    @Test
    public void unavailableFuelDoesNotBlockOpeningInPark() {
        Session session = new Session();
        for (int scenario = 0; scenario < 3; scenario++) {
            session.fuel = scenario == 0 ? null : new OemVehicleStateTransport.FuelLevel(52, -1);
            session.failFuel = scenario == 2;
            FuelFlapWidgetSnapshot state = FuelFlapWidgetSnapshot.read(session);
            assertTrue(state.canOpen);
            assertNull(state.refillLiters);
            assertNull(state.fuel);
        }
    }
}
