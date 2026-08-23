package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryHeatAutoPolicyTest {
    @Test
    public void currentColdEnabledDecisionCanSend() {
        assertTrue(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, true, false));
    }

    @Test
    public void everyMutableDecisionInputCanCancelQueuedFrames() {
        assertFalse(canSend(false, 7L, 7L, 7L, 11L, 11L,
                true, true, true, false));
        assertFalse(canSend(true, 6L, 7L, 7L, 11L, 11L,
                true, true, true, false));
        assertFalse(canSend(true, 7L, 7L, 6L, 11L, 11L,
                true, true, true, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 10L, 11L,
                true, true, true, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                false, true, true, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, false, true, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, true, true));
    }

    @Test
    public void providerCompletionMustMatchSubmittedSettingRevision() {
        assertTrue(BatteryHeatAutoPolicy.revisionCurrent(4L, 4L));
        assertFalse(BatteryHeatAutoPolicy.revisionCurrent(4L, 5L));
    }

    @Test
    public void temperatureOnlyRetriesProviderWhileSettingIsUnknown() {
        assertTrue(BatteryHeatAutoPolicy.settingRefreshNeededForTemperature(false));
        assertFalse(BatteryHeatAutoPolicy.settingRefreshNeededForTemperature(true));
    }

    private static boolean canSend(boolean activeInstance,
                                   long expectedEpoch, long activeEpoch, long ambientEpoch,
                                   long expectedDecision, long currentDecision,
                                   boolean enabled, boolean known, boolean cold,
                                   boolean controlActive) {
        return BatteryHeatAutoPolicy.canSend(
                activeInstance, expectedEpoch, activeEpoch, ambientEpoch,
                expectedDecision, currentDecision,
                enabled, known, cold, controlActive);
    }
}
