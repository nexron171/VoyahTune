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
        OemClimateState state = state(0, 0, 0, 0);
        assertEquals(21.0, state.values().get(CarPlayClimateProtocol.DRIVER_TEMPERATURE), 0);
        assertEquals(0.0, state.values().get(CarPlayClimateProtocol.POWER), 0);
        assertEquals(1.0, state.values().get(CarPlayClimateProtocol.SYNC), 0);
        assertEquals(1.0, state.values().get(CarPlayClimateProtocol.RECIRCULATION), 0);
        assertEquals(19, state.outsideTemperature);
        assertFalse(state(-1, -1, -1, 2).values().containsKey(CarPlayClimateProtocol.POWER));
        assertFalse(
                state(-1, -1, -1, -1).values().containsKey(CarPlayClimateProtocol.RECIRCULATION));
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
        assertEquals(4, manual.value);
        assertNull(ClimateCommand.from(state(1, 1, 0, 1), CarPlayClimateProtocol.AUTO, 0));
        assertEquals(
                "AC_POWER_SWITCH",
                ClimateCommand.from(state(1, 0, 3, 1), CarPlayClimateProtocol.FAN, 0).field);
    }

    @Test
    public void fanCommandsUseOemEncodingAndKeepReportedLevelsUnshifted() {
        for (int level = 1; level <= 7; level++) {
            OemClimateState observed = state(1, 0, level, 1);
            ClimateCommand command =
                    ClimateCommand.from(observed, CarPlayClimateProtocol.FAN, level);
            assertEquals("AC_BLOWER", command.field);
            assertEquals(level + 1, command.value);
            assertEquals(level, observed.values().get(CarPlayClimateProtocol.FAN), 0);
        }
        assertFalse(ClimateCommand.valid(CarPlayClimateProtocol.FAN, 8));
        assertFalse(ClimateCommand.valid(CarPlayClimateProtocol.FAN, 1.5));
        assertFalse(state(1, 0, 8, 1).values().containsKey(CarPlayClimateProtocol.FAN));
        assertFalse(ClimateCommand.reached(state(1, 0, 0, 1), CarPlayClimateProtocol.FAN, 0));
        assertTrue(ClimateCommand.reached(state(0, 0, 0, 1), CarPlayClimateProtocol.FAN, 0));
    }

    @Test
    public void circulationReportsOemInternalExternalAndAutomaticModesSeparately() {
        double[][] expected = {{1, 0}, {0, 0}, {0, 1}};
        for (int mode = 0; mode <= 2; mode++) {
            OemClimateState observed = state(1, 0, 3, mode);
            assertEquals(
                    expected[mode][0],
                    observed.values().get(CarPlayClimateProtocol.RECIRCULATION),
                    0);
            assertEquals(
                    expected[mode][1],
                    observed.values().get(CarPlayClimateProtocol.RECIRCULATION_AUTO),
                    0);
            assertEquals(0.0, observed.values().get(CarPlayClimateProtocol.AUTO), 0);
        }
        for (int invalid : new int[] {-1, 3, 255}) {
            OemClimateState unknown = state(1, 1, 3, invalid);
            assertFalse(unknown.values().containsKey(CarPlayClimateProtocol.RECIRCULATION));
            assertFalse(unknown.values().containsKey(CarPlayClimateProtocol.RECIRCULATION_AUTO));
        }
    }

    @Test
    public void circulationCyclesOnOffAutoFromTheFreshOemMode() {
        int[] observedModes = {0, 1, 2, 0};
        for (int index = 0; index < observedModes.length - 1; index++) {
            OemClimateState observed = state(1, 0, 3, observedModes[index]);
            assertEquals(observedModes[index + 1], ClimateCommand.nextCirculationMode(observed));
            assertFalse(
                    ClimateCommand.reached(observed, CarPlayClimateProtocol.CYCLE_RECIRCULATION, 1));
            ClimateCommand command =
                    ClimateCommand.from(observed, CarPlayClimateProtocol.CYCLE_RECIRCULATION, 1);
            assertEquals("AC_RECIRC_AIR", command.field);
            assertEquals(11, command.stableId);
            assertEquals(1, command.value);
        }
        for (double invalid : new double[] {0, 2, 0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(ClimateCommand.valid(CarPlayClimateProtocol.CYCLE_RECIRCULATION, invalid));
        }
        OemClimateState unknown = state(1, 0, 3, -1);
        assertEquals(-1, ClimateCommand.nextCirculationMode(unknown));
        assertNull(ClimateCommand.from(unknown, CarPlayClimateProtocol.CYCLE_RECIRCULATION, 1));
        assertFalse(ClimateCommand.valid(CarPlayClimateProtocol.RECIRCULATION_AUTO, 1));
    }

    @Test
    public void absoluteCirculationCommandsLeaveAutoAndRequireConfirmedProgress() {
        OemClimateState internal = state(1, 0, 3, 0);
        OemClimateState external = state(1, 0, 3, 1);
        OemClimateState automatic = state(1, 0, 3, 2);
        OemClimateState unknown = state(1, 0, 3, -1);
        assertTrue(ClimateCommand.reached(external, CarPlayClimateProtocol.RECIRCULATION, 0));
        assertTrue(ClimateCommand.reached(internal, CarPlayClimateProtocol.RECIRCULATION, 1));
        assertFalse(ClimateCommand.reached(automatic, CarPlayClimateProtocol.RECIRCULATION, 0));
        assertFalse(ClimateCommand.reached(automatic, CarPlayClimateProtocol.RECIRCULATION, 1));
        assertNotNull(ClimateCommand.from(automatic, CarPlayClimateProtocol.RECIRCULATION, 0));
        assertNotNull(ClimateCommand.from(automatic, CarPlayClimateProtocol.RECIRCULATION, 1));
        assertNull(ClimateCommand.from(unknown, CarPlayClimateProtocol.RECIRCULATION, 1));
        assertTrue(ClimateCommand.circulationAdvanced(external, automatic));
        assertTrue(ClimateCommand.circulationAdvanced(automatic, external));
        assertFalse(ClimateCommand.circulationAdvanced(external, external));
        assertFalse(ClimateCommand.circulationAdvanced(external, unknown));
    }
}
