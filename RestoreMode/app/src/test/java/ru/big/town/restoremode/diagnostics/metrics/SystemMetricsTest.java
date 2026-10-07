package ru.big.town.restoremode.diagnostics.metrics;

import static org.junit.Assert.*;

import org.junit.Test;

public class SystemMetricsTest {
    @Test
    public void cpuUsesAggregateDeltaAndDoesNotDoubleCountGuest() {
        SystemCpuSample before = SystemCpuSample.parse("cpu 100 10 20 800 50 10 5 5 40 2");
        SystemCpuSample after = SystemCpuSample.parse("cpu 140 10 30 835 65 10 5 5 70 5");
        assertEquals(1000, before.total);
        assertEquals(850, before.idle);
        assertEquals(50f, after.percentSince(before), .001f);
        assertEquals(
                0f,
                SystemCpuSample.parse("cpu 100 10 20 900 50 10 5 5").percentSince(before),
                .001f);
        assertEquals(
                100f,
                SystemCpuSample.parse("cpu 200 10 20 800 50 10 5 5").percentSince(before),
                .001f);
    }

    @Test
    public void unavailableResetAndInvalidCountersNeverBecomeZero() {
        SystemCpuSample baseline = SystemCpuSample.parse("cpu 10 0 0 90");
        assertTrue(Float.isNaN(baseline.percentSince(null)));
        assertTrue(Float.isNaN(baseline.percentSince(baseline)));
        assertTrue(Float.isNaN(SystemCpuSample.parse("cpu 5 0 0 45").percentSince(baseline)));
        assertTrue(Float.isNaN(SystemCpuSample.parse("cpu 30 0 0 80").percentSince(baseline)));
        assertNull(SystemCpuSample.parse(null));
        assertNull(SystemCpuSample.parse("cpu0 1 2 3 4"));
        assertNull(SystemCpuSample.parse("cpu 1 2 3 invalid"));
        assertNull(SystemCpuSample.parse("cpu 1 2 3 -1"));
        assertNull(SystemCpuSample.parse("cpu 9223372036854775807 1 0 0"));
    }

    @Test
    public void ramUsesTotalMinusAvailableAndValidatesTheSource() {
        assertEquals(75f, SystemCpuSample.ramPercent(8000, 2000), .001f);
        assertEquals(0f, SystemCpuSample.ramPercent(8000, 8000), .001f);
        assertEquals(100f, SystemCpuSample.ramPercent(8000, 0), .001f);
        assertTrue(Float.isNaN(SystemCpuSample.ramPercent(0, 0)));
        assertTrue(Float.isNaN(SystemCpuSample.ramPercent(8000, 9000)));
    }

    @Test
    public void cpuBaselineResetsOnPauseGapAndUnreadableSample() {
        SystemCpuSample.Tracker tracker = new SystemCpuSample.Tracker();
        SystemCpuSample a = SystemCpuSample.parse("cpu 100 0 0 900");
        SystemCpuSample b = SystemCpuSample.parse("cpu 150 0 0 950");
        assertTrue(Float.isNaN(tracker.sample(a, 0, 1)));
        assertEquals(50f, tracker.sample(b, 5000, 1), .001f);
        assertTrue(Float.isNaN(tracker.sample(a, 10000, 2)));
        assertEquals(50f, tracker.sample(b, 15000, 2), .001f);
        assertTrue(Float.isNaN(tracker.sample(a, 30000, 2)));
        assertTrue(Float.isNaN(tracker.sample(null, 35000, 2)));
        assertTrue(Float.isNaN(tracker.sample(a, 40000, 2)));
        assertEquals(50f, tracker.sample(b, 45000, 2), .001f);
    }

    @Test
    public void localSnapshotPreservesUnavailableRamAndCpuIndependently() {
        SystemMetricsReader.Snapshot snapshot =
                new SystemMetricsReader.Snapshot(5000, 8000, 2000, Float.NaN, false);
        assertEquals(75f, snapshot.ramPercent, .001f);
        assertEquals(6000L, snapshot.usedMemoryBytes);
        assertTrue(Float.isNaN(snapshot.cpuPercent));
        assertFalse(snapshot.cpuReadable);
        SystemMetricsReader.Snapshot missingRam =
                new SystemMetricsReader.Snapshot(5000, 0, -1, 50, true);
        assertTrue(Float.isNaN(missingRam.ramPercent));
        assertEquals(50f, missingRam.cpuPercent, .001f);
    }
}
