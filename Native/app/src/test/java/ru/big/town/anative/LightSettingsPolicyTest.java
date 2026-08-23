package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LightSettingsPolicyTest {
    @Test
    public void cabinFallbackNeedsThresholds() {
        assertTrue(LightSettingsPolicy.needsThresholds(
                false, false, false, false, false));
    }

    @Test
    public void manualAutoCancelsGuardedRequest() {
        assertFalse(LightSettingsPolicy.needsThresholds(
                true, true, false, false, false));
    }

    @Test
    public void completedIfUnsentRequestDoesNotReadProvider() {
        assertFalse(LightSettingsPolicy.needsThresholds(
                false, false, true, true, false));
    }

    @Test
    public void outdoorReasonDoesNotNeedCabinThresholds() {
        assertFalse(LightSettingsPolicy.needsThresholds(
                false, false, false, false, true));
    }
}
