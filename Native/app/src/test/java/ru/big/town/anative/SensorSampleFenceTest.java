package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SensorSampleFenceTest {
    @Test
    public void callbackQueuedBeforeSettingsCompletionIsRejected() {
        SensorSampleFence fence = new SensorSampleFence(5L, 20L);

        assertFalse(fence.accepts(19L, 0L));
        assertFalse(fence.accepts(20L, 0L));
    }

    @Test
    public void newerLiveCallbackIsAccepted() {
        SensorSampleFence fence = new SensorSampleFence(5L, 20L);

        assertTrue(fence.accepts(21L, 0L));
    }

    @Test
    public void freshQueryCarriesExactSettingsGeneration() {
        SensorSampleFence fence = new SensorSampleFence(5L, 20L);

        assertTrue(fence.accepts(20L, 5L));
        assertFalse(fence.accepts(20L, 4L));
        assertFalse(fence.accepts(20L, 6L));
    }
}
