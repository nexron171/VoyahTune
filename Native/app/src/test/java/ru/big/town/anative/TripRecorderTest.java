package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;
import ru.big.town.common.EnergyWidgetSettings;

public class TripRecorderTest {
    private void sample(TripRecorder r, double meters, long time, double lat, double lon) {
        r.location(lat, lon, 5, time, time, false); r.sample(meters, 80, 50, time, 10000 + time);
    }
    private long trackCount(TripRecorder r) { return r.pending.stream().filter(p -> p.kind.equals("track")).count(); }
    @Test public void odometerDecimatesAt100mAndLocalTimeDefinesSegmentSpeed() {
        TripRecorder r = new TripRecorder(); sample(r, 1_000_000, 0, 55, 37);
        sample(r, 1_000_040, 3000, 55.0002, 37); assertEquals(1, trackCount(r));
        sample(r, 1_000_100, 6000, 55.0008, 37); assertEquals(2, trackCount(r));
        double[] p = r.pending.get(3).values;
        assertEquals(100, p[4], 0); assertEquals(6000, p[5], 0); assertEquals(60, p[6], 0);
        assertEquals(100, r.distanceMeters, .001);
    }
    @Test public void ignoresOdometerSegmentsOver200AndDoesNotBridgeRejectedPoint() {
        TripRecorder r = new TripRecorder(); sample(r, 1_000_000, 0, 55, 37);
        sample(r, 1_000_100, 1000, 55.0008, 37); assertEquals(1, trackCount(r));
        sample(r, 1_000_200, 4000, 55.0016, 37); assertEquals(2, trackCount(r));
        assertEquals(1, r.pending.get(4).values[7], 0);
    }
    @Test public void coordinateTeleportAndRepeatedSpoofAreRejectedWhileDistanceStaysOdometerBased() {
        TripRecorder r = new TripRecorder(); sample(r, 1_000_000, 0, 55, 37);
        r.location(60, 45, 100, 5000, 5000, false);
        sample(r, 1_000_100, 6000, 60, 45); sample(r, 1_000_200, 12000, 60.0005, 45);
        assertEquals(1, trackCount(r)); assertEquals(200, r.distanceMeters, 0);
        sample(r, 1_000_300, 18000, 55.002, 37); assertEquals(2, trackCount(r));
        assertEquals(1, r.pending.get(r.pending.size() - 1).values[7], 0);
    }
    @Test public void rejectsMockStaleInaccurateAndOutOfRangeLocations() {
        TripRecorder r = new TripRecorder(); r.location(55, 37, 5, 0, 0, true); r.sample(1000, 80, 50, 0, 1000);
        r.location(55, 37, 5, 0, 20000, false); r.sample(1100, 80, 50, 20000, 21000);
        r.location(55, 37, 100, 24000, 24000, false); r.sample(1200, 80, 50, 24000, 25000);
        r.location(91, 37, 5, 30000, 30000, false); r.sample(1300, 80, 50, 30000, 31000);
        assertEquals(0, trackCount(r));
    }
    @Test public void observationsAcrossPauseRestartAndRollbackNeverProduceArtificialSegments() {
        TripRecorder r = new TripRecorder(); sample(r, 1000, 0, 55, 37); sample(r, 1100, 6000, 55.0008, 37);
        r.breakObservation(); sample(r, 5000, 12000, 55.02, 37);
        assertEquals(100, r.distanceMeters, 0); assertEquals(1, r.pending.get(5).values[7], 0);
        sample(r, 1000, 18000, 55.021, 37); assertEquals(100, r.distanceMeters, 0);
    }
    @Test public void levelsUseDownwardStepsAndKeepReplenishmentOutOfConsumption() {
        TripRecorder r = new TripRecorder(); r.sample(1000, 80, 50, 0, 1000);
        r.sample(1100, 79, 49, 6000, 7000); r.sample(1100, 90, 80, 7000, 8000);
        r.sample(1200, 89, 79, 12000, 13000);
        assertEquals(80, r.startSoc, 0); assertEquals(89, r.endSoc, 0);
        assertEquals(2, r.evDrop, 0); assertEquals(2, r.fuelDrop, 0); assertEquals(200, r.evMeters, 0);
    }
    @Test public void tripTotalsAndEnergyPointsIncludeSmallRecoveryWithAnInclusiveBoundary() {
        TripRecorder r = new TripRecorder(); r.sample(1000, 80, 50, 0, 1000);
        r.sample(1100, 79, 49, 6000, 7000);
        r.sample(1200, 80, 49.5, 12000, 13000);
        assertEquals(0, r.evDrop, 0); assertEquals(1, r.fuelDrop, 0);
        assertEquals(200, r.evMeters, 0); assertEquals(0, r.pending.get(2).values[4], 0);
        r.sample(1300, 79, 49, 18000, 19000);
        assertEquals(1, r.evDrop, 0); assertEquals(1.5, r.fuelDrop, 0);
    }
    @Test public void continuousChargingAcrossRepeatedLevelsIsExcludedAndGapsPreserveTotals() {
        TripRecorder r = new TripRecorder(); r.sample(1000, 80, 50, 0, 1000);
        r.sample(1100, 79, 50, 6000, 7000);
        r.sample(1200, 79.6, 50, 12000, 13000);
        r.sample(1200, 79.6, 50, 13000, 14000);
        r.sample(1300, 80.1, 50, 18000, 19000);
        assertEquals(1, r.evDrop, .00001);
        r.sample(1400, 81, 50, 24000, 25000);
        assertEquals(1, r.evDrop, .00001);
        r.breakObservation(); r.sample(1500, 90, 50, 30000, 31000);
        assertEquals(1, r.evDrop, .00001); assertEquals(400, r.evMeters, .00001);
    }
    @Test public void chargeAtStartIsCapturedEvenBeforeAnOdometerSampleIsAvailable() {
        TripRecorder r = new TripRecorder(); r.sample(Double.NaN, 80, 50, 0, 1000);
        assertEquals(80, r.startSoc, 0); assertEquals(50, r.startFuel, 0);
        assertTrue(r.pending.isEmpty()); assertEquals(0, r.distanceMeters, 0);
        r.sample(1000, 79, 49, 6000, 7000);
        assertEquals(80, r.startSoc, 0); assertEquals(79, r.endSoc, 0);
        assertEquals(0, r.evDrop, 0); assertEquals(0, r.fuelDrop, 0);
    }
    @Test public void rawOdometerTicksAreExactAndInvalidFramesStayUnavailable() {
        EnergyTelemetrySample p = EnergyTelemetrySample.decode(EnergyTelemetrySample.PRECISE_ODOMETER,
                new int[]{0xd4, 0x58, 0x02, 0, 0, 0, 0, 0});
        assertEquals(153812, p.values[0], 0);
        assertTrue(TripRecorder.preciseOdometerCompatible(15381, p.values[0]));
        assertFalse(TripRecorder.preciseOdometerCompatible(15383, p.values[0]));
        assertFalse(TripRecorder.preciseOdometerCompatible(15381, Double.NaN));
        assertTrue(Float.isNaN(EnergyTelemetrySample.decode(EnergyTelemetrySample.PRECISE_ODOMETER, new int[0]).values[0]));
    }
    @Test public void confirmedDriveAfterRestartRecoversOdometerDistanceButBreaksTrackAndLevels() {
        TripRecorder r = new TripRecorder(); r.distanceMeters = 500; r.resumeOdo = 10000;
        sample(r, 10200, 100, 55, 37);
        assertEquals(700, r.distanceMeters, 0); assertEquals(0, r.evDrop, 0);
        assertEquals(1, r.pending.get(1).values[7], 0);
    }
    @Test public void tripAverageUsesFullObservedTwentyTwoKmAndOffsetsSmallSocCycles() {
        for (boolean fluctuations : new boolean[]{false, true}) {
            TripRecorder r = new TripRecorder(); r.sample(100_000, 80, 50, 0, 1_000_000);
            for (int i = 1; i <= 220; i++) {
                double odo = 100_000 + i * 100; float soc = 80 - 10 * i / 220f;
                long elapsed = i * 6000L;
                r.sample(odo, soc, 50, elapsed, 1_000_000 + elapsed);
                if (fluctuations && i % 20 == 0 && i <= 200) {
                    float[] values = {soc + .5f, soc + .5f, soc};
                    for (int j = 0; j < values.length; j++)
                        r.sample(odo, values[j], 50, elapsed + j + 1, 1_000_000 + elapsed + j + 1);
                }
            }
            assertEquals(80, r.startSoc, 0); assertEquals(70, r.endSoc, 0);
            assertEquals(22_000, r.distanceMeters, .00001); assertEquals(22_000, r.evMeters, .00001);
            assertEquals(10, r.evDrop, .00001); assertEquals(0, r.fuelDrop, 0);
            assertEquals(19.54545, EnergyWidgetSettings.electricityAverage(r.evDrop, r.evMeters / 1000, 43), .0001);
            assertEquals(10, r.pending.get(r.pending.size() - 1).values[4], .00001);
        }
    }
}
