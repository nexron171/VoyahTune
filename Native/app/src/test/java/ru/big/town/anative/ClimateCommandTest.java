package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Arrays;

public class ClimateCommandTest {
    static OemClimateState state(int power, int automatic, int fan, int circulation) {
        int[] fields = new int[36];
        Arrays.fill(fields, -1);
        fields[0] = power;
        fields[1] = power;
        fields[4] = 0;
        fields[10] = fan;
        fields[11] = Float.floatToIntBits(21.0f);
        fields[12] = Float.floatToIntBits(21.0f);
        fields[14] = circulation;
        fields[22] = automatic;
        fields[34] = -9999;
        fields[35] = 19;
        return new OemClimateState(fields);
    }

    @Test
    public void usesObservedStateAndPreservesUnknownFields() {
        OemClimateState state = state(0, 0, 0, 1);
        assertEquals(21.0, state.values().get(CarPlayClimateProtocol.DRIVER_TEMPERATURE), 0);
        assertEquals(0.0, state.values().get(CarPlayClimateProtocol.POWER), 0);
        assertEquals(1.0, state.values().get(CarPlayClimateProtocol.SYNC), 0);
        assertEquals(0.0, state.values().get(CarPlayClimateProtocol.RECIRCULATION), 0);
        assertEquals(19, state.outsideTemperature);
        assertFalse(state(-1, -1, -1, 2).values().containsKey(CarPlayClimateProtocol.POWER));
        assertFalse(
                state(-1, -1, -1, 2).values().containsKey(CarPlayClimateProtocol.RECIRCULATION));
    }

    @Test
    public void temperaturesUseTenthsAndRejectNonStepsOrNonFiniteValues() {
        ClimateCommand command =
                ClimateCommand.from(
                        state(1, 0, 3, 1), CarPlayClimateProtocol.DRIVER_TEMPERATURE, 21.5);
        assertEquals("AC_LEFT_TEMP", command.field);
        assertEquals(3, command.stableId);
        assertEquals(215, command.value);
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 14, 31, 21.25}) {
            assertFalse(ClimateCommand.valid(CarPlayClimateProtocol.DRIVER_TEMPERATURE, invalid));
        }
    }

    @Test
    public void powerAndCompressorAreConditionalPulsesNotAbsoluteBooleans() {
        for (String field :
                new String[] {CarPlayClimateProtocol.POWER, CarPlayClimateProtocol.COMPRESSOR}) {
            assertTrue(ClimateCommand.reached(state(0, 0, 0, 1), field, 0));
            assertFalse(ClimateCommand.reached(state(0, 0, 0, 1), field, 1));
            assertEquals(1, ClimateCommand.from(state(0, 0, 0, 1), field, 1).value);
            assertEquals(1, ClimateCommand.from(state(1, 0, 3, 1), field, 0).value);
            assertNull(ClimateCommand.from(state(-1, 0, 3, 1), field, 1));
        }
    }

    @Test
    public void manualFanEndsAutoEvenWhenTheObservedLevelAlreadyMatches() {
        assertFalse(ClimateCommand.reached(state(1, 1, 3, 1), CarPlayClimateProtocol.FAN, 3));
        assertTrue(ClimateCommand.reached(state(1, 0, 3, 1), CarPlayClimateProtocol.FAN, 3));
        ClimateCommand manual =
                ClimateCommand.from(state(1, 1, 3, 1), CarPlayClimateProtocol.AUTO, 0);
        assertEquals("AC_BLOWER", manual.field);
        assertEquals(3, manual.value);
        assertNull(ClimateCommand.from(state(1, 1, 0, 1), CarPlayClimateProtocol.AUTO, 0));
        assertEquals(
                "AC_POWER_SWITCH",
                ClimateCommand.from(state(1, 0, 3, 1), CarPlayClimateProtocol.FAN, 0).field);
    }
}
