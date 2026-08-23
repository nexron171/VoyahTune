package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LightSettingsPolicyTest {
    @Test
    public void cabinFallbackNeedsThresholds() {
        assertEquals(LightSettingsPolicy.Decision.NEED_THRESHOLDS,
                LightSettingsPolicy.decide(false, false, false, false, false));
    }

    @Test
    public void manualAutoCancelsGuardedRequest() {
        assertEquals(LightSettingsPolicy.Decision.COMPLETE_CANCELLED,
                LightSettingsPolicy.decide(true, true, false, false, false));
    }

    @Test
    public void completedIfUnsentRequestDoesNotReadProvider() {
        assertEquals(LightSettingsPolicy.Decision.COMPLETE_CANCELLED,
                LightSettingsPolicy.decide(false, false, true, true, false));
    }

    @Test
    public void outdoorReasonDoesNotNeedCabinThresholds() {
        assertEquals(LightSettingsPolicy.Decision.APPLY_WITHOUT_THRESHOLDS,
                LightSettingsPolicy.decide(false, false, false, false, true));
    }
}
