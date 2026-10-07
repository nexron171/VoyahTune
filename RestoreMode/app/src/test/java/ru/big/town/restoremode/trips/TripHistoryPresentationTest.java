package ru.big.town.restoremode.trips;

import static org.junit.Assert.*;

import org.junit.Test;

public class TripHistoryPresentationTest {
    @Test
    public void averagesUseOriginalTripAmountsAndOdometerDistance() {
        assertEquals(17.2, TripHistoryPresentation.averagePer100(1.032, 6), .00001);
        assertEquals(5.6, TripHistoryPresentation.averagePer100(.336, 6), .00001);
        assertEquals(0, TripHistoryPresentation.averagePer100(0, 6), 0);
    }

    @Test
    public void averagesDoNotInventMissingDataOrDivideByZero() {
        for (double amount : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertTrue(Double.isNaN(TripHistoryPresentation.averagePer100(amount, 6)));
        }
        for (double km : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertTrue(Double.isNaN(TripHistoryPresentation.averagePer100(1.032, km)));
        }
        assertTrue(Double.isNaN(TripHistoryPresentation.averagePer100(Double.MAX_VALUE, .01)));
    }

    @Test
    public void speedColorsMatchAllBoundariesIncludingFractionalSpeeds() {
        double[] speeds = {0, 25, 25.9, 26, 59, 59.9, 60, 79, 79.9, 80, 129, 129.9, 130, 200};
        int[] bands = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4};
        for (int i = 0; i < speeds.length; i++) {
            assertEquals(
                    TripHistoryPresentation.COLORS[bands[i]],
                    TripHistoryPresentation.speedColor(speeds[i]));
        }
    }

    @Test
    public void routeNeverConnectsAcrossMissingOrRejectedObservations() {
        double[] p = {0, 1000, 55, 37, 0, 0, 0, 1}, q = {.1, 7000, 55, 37, 100, 6000, 60, 0};
        assertTrue(TripHistoryPresentation.drawableSegment(p, q));
        q[7] = 1;
        assertFalse(TripHistoryPresentation.drawableSegment(p, q));
        q[7] = 0;
        q[6] = 200.01;
        assertFalse(TripHistoryPresentation.drawableSegment(p, q));
        q[6] = 60;
        q[0] = 0;
        assertFalse(TripHistoryPresentation.drawableSegment(p, q));
    }

    @Test
    public void estimatedRatesUseIndependentMeasuredDistanceAndNeverInventUnavailableSamples() {
        assertEquals(8.6, TripHistoryPresentation.rate(1, 1.2, 1, 2, 43), .0001);
        assertEquals(11.2, TripHistoryPresentation.rate(1, 1.2, 1, 2, 56), .0001);
        assertTrue(Double.isNaN(TripHistoryPresentation.rate(0, 1, 1, 1, 43)));
        assertTrue(Double.isNaN(TripHistoryPresentation.rate(0, Double.NaN, 0, 1, 43)));
    }

    @Test
    public void electricAveragesAndRatesCanRepresentRecoveryWhileFuelCannot() {
        assertEquals(-2.15, TripHistoryPresentation.averagePer100(-.215, 10, true), .00001);
        assertEquals(-21.5, TripHistoryPresentation.rate(1, .5, 1, 2, 43, true), .00001);
        assertTrue(Double.isNaN(TripHistoryPresentation.averagePer100(-.215, 10)));
        assertTrue(Double.isNaN(TripHistoryPresentation.rate(1, .5, 1, 2, 56)));
        assertTrue(Double.isNaN(TripHistoryPresentation.rate(1, .5, 1, 1, 43, true)));
        assertTrue(
                Double.isNaN(
                        TripHistoryPresentation.rate(
                                1, .5, 1, Double.POSITIVE_INFINITY, 43, true)));
    }
}
