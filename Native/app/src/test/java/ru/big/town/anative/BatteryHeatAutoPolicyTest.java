package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryHeatAutoPolicyTest {
    @Test
    public void currentColdEnabledDecisionCanSend() {
        assertTrue(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, true, false, false));
    }

    @Test
    public void everyMutableDecisionInputCanCancelQueuedFrames() {
        assertFalse(canSend(false, 7L, 7L, 7L, 11L, 11L,
                true, true, true, false, false));
        assertFalse(canSend(true, 6L, 7L, 7L, 11L, 11L,
                true, true, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 6L, 11L, 11L,
                true, true, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 10L, 11L,
                true, true, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                false, true, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, false, true, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, false, false, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, true, true, false));
        assertFalse(canSend(true, 7L, 7L, 7L, 11L, 11L,
                true, true, true, false, true));
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

    @Test
    public void platformSignalsStaySeparated() {
        assertTrue(BatteryHeatAutoPolicy.platform(true, false)
                == BatteryHeatAutoPolicy.PLATFORM_H97X);
        assertTrue(BatteryHeatAutoPolicy.platform(false, true)
                == BatteryHeatAutoPolicy.PLATFORM_H97C);
        assertTrue(BatteryHeatAutoPolicy.platform(false, false)
                == BatteryHeatAutoPolicy.PLATFORM_UNKNOWN);
    }

    @Test
    public void bothFirmwareFeedbacksAndBmsConfirmHeating() {
        assertTrue(BatteryHeatAutoPolicy.heatingActive(1, 0, 0));
        assertTrue(BatteryHeatAutoPolicy.heatingActive(0, 1, 0));
        assertTrue(BatteryHeatAutoPolicy.heatingActive(0, 0, 9));
        assertFalse(BatteryHeatAutoPolicy.heatingActive(0, 0, 0));
        assertTrue(BatteryHeatAutoPolicy.controlBusy(2, 0, 0, 0, false));
        assertTrue(BatteryHeatAutoPolicy.controlBusy(0, 1, 0, 0, false));
        assertTrue(BatteryHeatAutoPolicy.controlBusy(0, 0, 0, 0, true));
        assertTrue(BatteryHeatAutoPolicy.activationConfirmed(
                BatteryHeatAutoPolicy.PLATFORM_H97C, 0, 1, 0, 0));
        assertFalse(BatteryHeatAutoPolicy.activationConfirmed(
                BatteryHeatAutoPolicy.PLATFORM_H97X, 0, 1, 0, 0));
    }

    @Test
    public void h97xZeroClearsItsFailureWithoutH97cFallback() {
        final int unknown = Integer.MIN_VALUE;
        assertTrue(BatteryHeatAutoPolicy.effectiveFailure(
                BatteryHeatAutoPolicy.PLATFORM_H97X, 3, unknown, unknown) == 3);
        assertTrue(BatteryHeatAutoPolicy.effectiveFailure(
                BatteryHeatAutoPolicy.PLATFORM_H97X, 0, unknown, unknown) == 0);
        assertTrue(BatteryHeatAutoPolicy.blockingFailure(1));
        assertFalse(BatteryHeatAutoPolicy.blockingFailure(0));
    }

    @Test
    public void eitherCompleteFirmwareSnapshotStopsRecoveryReads() {
        int xMask = 0b00111;
        int cMask = 0b11001;
        assertTrue(BatteryHeatAutoPolicy.snapshotComplete(xMask, xMask, cMask));
        assertTrue(BatteryHeatAutoPolicy.snapshotComplete(cMask, xMask, cMask));
        assertFalse(BatteryHeatAutoPolicy.snapshotComplete(0b00001, xMask, cMask));
    }

    private static boolean canSend(boolean activeInstance,
                                   long expectedEpoch, long activeEpoch, long ambientEpoch,
                                   long expectedDecision, long currentDecision,
                                   boolean enabled, boolean known, boolean cold,
                                   boolean controlBusy, boolean controlBlocked) {
        return BatteryHeatAutoPolicy.canSend(
                activeInstance, expectedEpoch, activeEpoch, ambientEpoch,
                expectedDecision, currentDecision,
                enabled, known, cold, controlBusy, controlBlocked);
    }
}
