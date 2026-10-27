package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Arrays;

public class ClimateAirDistributionTest {
    private static final String UPPER = CarPlayClimateProtocol.VENT_UPPER;
    private static final String MIDDLE = CarPlayClimateProtocol.VENT_MIDDLE;
    private static final String LOWER = CarPlayClimateProtocol.VENT_LOWER;

    private static OemClimateState state(int mode, int power, int automatic) {
        int[] fields = new int[36];
        Arrays.fill(fields, -1);
        fields[0] = power;
        fields[8] = mode;
        fields[22] = automatic;
        return new OemClimateState(fields);
    }

    @Test
    public void decodesAllFiveStockModesWithoutInventingUnknownDirections() {
        int[][] directions = {{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 0, 0}};
        String[] fields = {UPPER, MIDDLE, LOWER};
        for (int mode = 0; mode < directions.length; mode++) {
            for (int direction = 0; direction < fields.length; direction++) {
                assertEquals(
                        directions[mode][direction],
                        state(mode, 1, 0).values().get(fields[direction]),
                        0);
            }
        }
        for (int mode : new int[] {-1, 5, 6, 7}) {
            assertFalse(state(mode, 1, 0).values().containsKey(UPPER));
            assertNull(ClimateCommand.from(state(mode, 1, 0), UPPER, 1));
        }
    }

    @Test
    public void upperAndMiddleReplaceEachOtherWhilePreservingFeet() {
        assertEquals(4, ClimateAirDistribution.targetMode(0, UPPER, true));
        assertEquals(3, ClimateAirDistribution.targetMode(1, UPPER, true));
        assertEquals(0, ClimateAirDistribution.targetMode(4, MIDDLE, true));
        assertEquals(1, ClimateAirDistribution.targetMode(3, MIDDLE, true));
        assertEquals(2, ClimateAirDistribution.targetMode(1, MIDDLE, false));
        assertEquals(2, ClimateAirDistribution.targetMode(3, UPPER, false));
    }

    @Test
    public void feetTogglePreservesOtherOutletsAndLastOutletCannotClose() {
        assertEquals(1, ClimateAirDistribution.targetMode(0, LOWER, true));
        assertEquals(0, ClimateAirDistribution.targetMode(1, LOWER, false));
        assertEquals(3, ClimateAirDistribution.targetMode(4, LOWER, true));
        assertEquals(4, ClimateAirDistribution.targetMode(3, LOWER, false));
        assertNull(ClimateCommand.from(state(0, 1, 0), MIDDLE, 0));
        assertNull(ClimateCommand.from(state(2, 1, 0), LOWER, 0));
        assertNull(ClimateCommand.from(state(4, 1, 0), UPPER, 0));
    }

    @Test
    public void usesAbsoluteModeAndRequiresManualReadbackOnPoweredHvac() {
        ClimateCommand command = ClimateCommand.from(state(1, 1, 1), UPPER, 1);
        assertEquals("AC_FLOW_MODE", command.field);
        assertEquals(13, command.stableId);
        assertEquals(3, command.value);
        assertFalse(ClimateCommand.reached(state(3, 1, 1), UPPER, 1));
        assertTrue(ClimateCommand.reached(state(3, 1, 0), UPPER, 1));
        assertFalse(ClimateCommand.reached(state(3, 0, 0), UPPER, 1));
        assertNull(ClimateCommand.from(state(1, 0, 0), UPPER, 1));
        assertFalse(ClimateCommand.valid(UPPER, 2));
    }

    @Test
    public void indexedVentUsesAllFiveOemModesAndRequiresManualReadback() {
        String field = CarPlayClimateProtocol.VENT_MODE;
        for (int current = 0; current <= 4; current++) {
            assertEquals(current, state(current, 1, 0).values().get(field), 0);
            for (int target = 0; target <= 4; target++) {
                ClimateCommand command = ClimateCommand.from(state(current, 1, 1), field, target);
                assertEquals("AC_FLOW_MODE", command.field);
                assertEquals(13, command.stableId);
                assertEquals(target, command.value);
                assertTrue(ClimateCommand.reached(state(target, 1, 0), field, target));
                assertFalse(ClimateCommand.reached(state(target, 1, 1), field, target));
                assertFalse(ClimateCommand.reached(state(target, 0, 0), field, target));
                assertNull(ClimateCommand.from(state(current, 0, 0), field, target));
            }
        }
        for (double invalid : new double[] {-1, 5, 1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertNull(ClimateCommand.from(state(0, 1, 0), field, invalid));
        }
        for (int unknown : new int[] {-1, 5, 255}) {
            assertFalse(state(unknown, 1, 0).values().containsKey(field));
            assertNull(ClimateCommand.from(state(unknown, 1, 0), field, 1));
        }
    }
}
