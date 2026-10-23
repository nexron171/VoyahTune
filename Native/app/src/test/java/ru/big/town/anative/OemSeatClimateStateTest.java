package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class OemSeatClimateStateTest {
    private static OemSeatClimateState state(
            OemSeatClimateState.Channel channel, int power, int level) {
        Map<OemVehicleStateTransport.StateKey, Integer> values = new HashMap<>();
        values.put(channel.power, power);
        values.put(channel.level, level);
        return new OemSeatClimateState(values);
    }

    @Test
    public void offIgnoresRememberedLevelAndOnReadsAllThreeLevels() {
        for (OemSeatClimateState.Channel channel : OemSeatClimateState.Channel.values()) {
            OemSeatClimateState off = state(channel, 1, 3);
            assertEquals(0.0, off.values().get(channel.onField), 0);
            assertEquals(0.0, off.values().get(channel.levelField), 0);
            for (int level = 1; level <= 3; level++) {
                OemSeatClimateState on = state(channel, 2, level);
                assertEquals(1.0, on.values().get(channel.onField), 0);
                assertEquals(level, on.values().get(channel.levelField), 0);
                assertTrue(on.reached(channel.levelField, level));
                assertFalse(off.reached(channel.levelField, level));
            }
        }
    }

    @Test
    public void levelsEnableChannelAndZeroUsesOffSwitchRatherThanLevelZero() {
        for (OemSeatClimateState.Channel channel : OemSeatClimateState.Channel.values()) {
            OemSeatClimateState before = state(channel, 1, 2);
            for (int level = 1; level <= 3; level++) {
                OemVehicleStateTransport.StateValue command =
                        before.command(channel.levelField, level);
                assertEquals(channel.level, command.key);
                assertEquals(level, command.value);
            }
            OemVehicleStateTransport.StateValue off = before.command(channel.levelField, 0);
            assertEquals(channel.power, off.key);
            assertEquals(1, off.value);
            OemVehicleStateTransport.StateValue on = before.command(channel.onField, 1);
            assertEquals(channel.level, on.key);
            assertEquals(2, on.value);
            assertEquals(0.0, before.values().get(channel.onField), 0);
        }
    }

    @Test
    public void unknownSwitchIsUnavailableAndInvalidLevelIsNeverReportedAsOff() {
        OemSeatClimateState.Channel channel = OemSeatClimateState.Channel.DRIVER_HEATING;
        for (int unknown : new int[] {-1, 0, 3, 255}) {
            OemSeatClimateState state = state(channel, unknown, 2);
            assertTrue(state.values().isEmpty());
            assertNull(state.command(channel.levelField, 3));
            assertFalse(state.reached(channel.onField, 0));
        }
        for (int unknown : new int[] {-1, 0, 4, 255}) {
            OemSeatClimateState state = state(channel, 2, unknown);
            assertEquals(1.0, state.values().get(channel.onField), 0);
            assertFalse(state.values().containsKey(channel.levelField));
            assertNull(state.command(channel.onField, 1));
            assertEquals(channel.power, state.command(channel.levelField, 0).key);
        }
    }

    @Test
    public void distinctSeatChannelsCannotChangeAnotherPositionOrInventRearVentilation() {
        assertEquals(12, new HashSet<>(OemSeatClimateState.KEYS).size());
        OemSeatClimateState.Channel rear = OemSeatClimateState.Channel.REAR_LEFT_HEATING;
        OemVehicleStateTransport.StateValue command = state(rear, 1, 2).command(rear.levelField, 3);
        assertEquals("REAR_SEAT_HEATING_COMMAND_LEFT", command.key.name);
        assertEquals(1030, command.key.stableId);
        assertEquals(1028, rear.power.stableId);
        assertEquals(1029, OemSeatClimateState.Channel.REAR_RIGHT_HEATING.level.stableId);
        assertEquals(1027, OemSeatClimateState.Channel.REAR_RIGHT_HEATING.power.stableId);
        assertEquals(263, OemSeatClimateState.Channel.DRIVER_VENTILATION.level.stableId);
        assertEquals(262, OemSeatClimateState.Channel.PASSENGER_VENTILATION.level.stableId);
        assertFalse(
                state(rear, 1, 2)
                        .values()
                        .containsKey(OemSeatClimateState.Channel.DRIVER_HEATING.levelField));
        assertNull(OemSeatClimateState.channel("rearLeftSeatVentilationLevel"));
    }

    @Test
    public void rejectsFractionalLevelsOutOfRangeValuesAndNonBooleanPower() {
        OemSeatClimateState.Channel channel = OemSeatClimateState.Channel.PASSENGER_VENTILATION;
        for (double invalid : new double[] {-1, 4, 1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(OemSeatClimateState.valid(channel.levelField, invalid));
        }
        assertFalse(OemSeatClimateState.valid(channel.onField, 2));
        assertFalse(OemSeatClimateState.valid("seat:driver:heat:on", 1));
    }
}
