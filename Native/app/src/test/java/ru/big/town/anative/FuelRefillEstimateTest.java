package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class FuelRefillEstimateTest {
    @Test public void estimatesFreeSpaceRatherThanRemainingFuel() {
        assertEquals(Integer.valueOf(39), FuelRefillEstimate.liters(52, 25));
        assertEquals(Integer.valueOf(26), FuelRefillEstimate.liters(52, 50));
        assertEquals(Integer.valueOf(13), FuelRefillEstimate.liters(52, 75));
    }

    @Test public void usesReportedCapacityAndHandlesFullAndEmptyTank() {
        assertEquals(Integer.valueOf(42), FuelRefillEstimate.liters(56, 25));
        assertEquals(Integer.valueOf(0), FuelRefillEstimate.liters(56, 100));
        assertEquals(Integer.valueOf(56), FuelRefillEstimate.liters(56, 0));
    }

    @Test public void missingAndInvalidDataDoNotBecomeAnEmptyTank() {
        for (int capacity : new int[]{-9999, -1, 0, 201, Integer.MAX_VALUE}) {
            assertNull(FuelRefillEstimate.liters(capacity, 50));
        }
        for (float percent : new float[]{-9999, -1, 101, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertNull(FuelRefillEstimate.liters(52, percent));
        }
    }
}
