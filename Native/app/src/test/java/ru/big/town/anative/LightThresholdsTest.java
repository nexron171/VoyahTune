package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LightThresholdsTest {
    @Test
    public void boundariesPreserveHeadlightHysteresis() {
        LightThresholds thresholds = new LightThresholds(3, 5);

        assertTrue(thresholds.desiredFor(3));
        assertNull(thresholds.desiredFor(4));
        assertNull(thresholds.desiredFor(5));
        assertFalse(thresholds.desiredFor(6));
        assertNull(thresholds.desiredFor(-1));
    }

    @Test
    public void invertedThresholdsAreNormalized() {
        LightThresholds thresholds = new LightThresholds(6, 2);

        assertEquals(2, thresholds.on);
        assertEquals(6, thresholds.off);
    }

    @Test
    public void defaultsMatchLegacyValues() {
        LightThresholds thresholds = LightThresholds.defaults();

        assertEquals(3, thresholds.on);
        assertEquals(5, thresholds.off);
    }
}
