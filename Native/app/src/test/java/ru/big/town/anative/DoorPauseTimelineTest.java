package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DoorPauseTimelineTest {

    @Test
    public void fadeReachesZeroExactlyAtConfiguredDeadline() {
        assertEquals(42L, DoorPauseTimeline.fadeStepDelayMs(1, 12, 500L));
        assertEquals(500L, DoorPauseTimeline.fadeStepDelayMs(12, 12, 500L));
        assertEquals(0, DoorPauseTimeline.fadeStepVolume(12, 12, 12));
    }

    @Test
    public void fadeVolumeIsMonotonic() {
        int previous = 20;
        for (int step = 1; step <= 12; step++) {
            int current = DoorPauseTimeline.fadeStepVolume(20, step, 12);
            assertTrue(current <= previous);
            previous = current;
        }
    }

    @Test
    public void remoteDrainWindowOutlivesFade() {
        assertEquals(2_200L, DoorPauseTimeline.restoreDelayMs(500L, 2_200L));
        assertEquals(2_200L, DoorPauseTimeline.restoreDelayMs(2_200L, 500L));
    }
}
