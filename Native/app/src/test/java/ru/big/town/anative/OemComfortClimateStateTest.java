package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class OemComfortClimateStateTest {
    private static final String WHEEL = CarPlayClimateProtocol.STEERING_WHEEL_HEATING_LEVEL;

    private static OemComfortClimateState wheel(int value) {
        return new OemComfortClimateState(
                Collections.singletonMap(OemComfortClimateState.STEERING_WHEEL_HEATING, value));
    }

    @Test
    public void wheelUsesAbsoluteSwitchAndRequiresObservedStateForSuccess() {
        OemComfortClimateState off = wheel(1);
        OemVehicleStateTransport.StateValue enable = off.command(WHEEL, 1);
        assertEquals("STEER_WHEEL_HEAT_SWITCH", enable.key.name);
        assertEquals(1098, enable.key.stableId);
        assertEquals(2, enable.value);
        assertEquals(0.0, off.values().get(WHEEL), 0);
        assertFalse(off.reached(WHEEL, 1));

        OemComfortClimateState on = wheel(2);
        assertTrue(on.reached(WHEEL, 1));
        assertEquals(1, on.command(WHEEL, 0).value);
        assertFalse(on.reached(WHEEL, 0));
        assertTrue(off.reached(WHEEL, 0));
    }

    @Test
    public void wheelFaultUnknownAndMissingStateNeverBecomeOffOrPermitCommands() {
        for (int value : new int[] {-1, 0, 3, 10, 255}) {
            OemComfortClimateState unavailable = wheel(value);
            assertFalse(unavailable.values().containsKey(WHEEL));
            assertFalse(unavailable.reached(WHEEL, 0));
            assertFalse(unavailable.reached(WHEEL, 1));
            assertNull(unavailable.command(WHEEL, 0));
            assertNull(unavailable.command(WHEEL, 1));
        }
        OemComfortClimateState missing = new OemComfortClimateState(Collections.emptyMap());
        assertTrue(missing.values().isEmpty());
        assertNull(missing.command(WHEEL, 1));
    }

    @Test
    public void wheelHasOneHeatingStageAndRejectsCoolingFractionsAndOtherFields() {
        assertTrue(OemComfortClimateState.handles(WHEEL));
        assertTrue(OemComfortClimateState.valid(WHEEL, 0));
        assertTrue(OemComfortClimateState.valid(WHEEL, 1));
        for (double invalid : new double[] {-1, 2, 3, 0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(OemComfortClimateState.valid(WHEEL, invalid));
            assertNull(wheel(1).command(WHEEL, invalid));
        }
        assertFalse(OemComfortClimateState.handles("wheel_heat:on"));
        assertFalse(OemComfortClimateState.valid("wheel_heat:on", 1));
        assertNull(wheel(1).command("wheel_heat:on", 1));
    }

    @Test
    public void wheelAvailabilityAndSeatCommandsAreIndependent() {
        OemSeatClimateState.Channel seat = OemSeatClimateState.Channel.REAR_LEFT_HEATING;
        Map<OemVehicleStateTransport.StateKey, Integer> raw = new HashMap<>();
        raw.put(seat.power, 2);
        raw.put(seat.level, 3);
        for (Integer wheelState : new Integer[] {null, 1, 2, 10}) {
            raw.remove(OemComfortClimateState.STEERING_WHEEL_HEATING);
            if (wheelState != null) {
                raw.put(OemComfortClimateState.STEERING_WHEEL_HEATING, wheelState);
            }
            OemComfortClimateState state = new OemComfortClimateState(raw);
            assertEquals(3.0, state.values().get(seat.levelField), 0);
            assertEquals(1.0, state.values().get(seat.onField), 0);
            assertEquals(seat.power, state.command(seat.levelField, 0).key);
            assertEquals(1, state.command(seat.levelField, 0).value);
            assertEquals(seat.level, state.command(seat.levelField, 2).key);
            assertTrue(OemComfortClimateState.handles(seat.levelField));
            assertTrue(OemComfortClimateState.valid(seat.levelField, 3));
            assertFalse(OemComfortClimateState.valid(seat.levelField, 4));
        }
        assertEquals(13, new HashSet<>(OemComfortClimateState.KEYS).size());
        assertEquals(Collections.singletonMap(WHEEL, 1.0), wheel(2).values());
    }
}
