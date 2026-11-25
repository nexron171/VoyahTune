package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ColdWiperStateTest {
    private static final int PARK = 0;
    private static final int REVERSE = 1;
    private static final int NEUTRAL = 2;
    private static final int DRIVE = 3;
    private static final int BATTERY = 4;
    private static final int[] NON_PARK_GEARS = {REVERSE, NEUTRAL, DRIVE, BATTERY};

    @Test
    public void initialOpenDoorAndEnablingOnAnAlreadyOpenDoorRequestParkingPosition() {
        ColdWiperState initial = new ColdWiperState(false, false);
        initial.setEnabled(true);
        initial.onDoor(1);
        assertEquals(Boolean.TRUE, initial.nextTarget());

        ColdWiperState enabledLater = new ColdWiperState(false, false);
        enabledLater.onDoor(1);
        assertNull(enabledLater.nextTarget());
        enabledLater.setEnabled(true);
        assertEquals(Boolean.TRUE, enabledLater.nextTarget());
    }

    @Test
    public void wakeRemoteHeatAndDisablingFeatureKeepAnAlreadyActivePosition() {
        ColdWiperState state = new ColdWiperState(true, false);
        state.setEnabled(false);
        state.onDoor(0);
        state.reconcile(true, state.positionUncertain());
        assertNull(state.nextTarget());
        for (int gear : new int[] {-1, PARK, 5}) {
            state.onGear(gear);
            state.reconcile(true, state.positionUncertain());
            assertNull(state.nextTarget());
        }
        state.onGear(REVERSE);
        assertEquals(Boolean.FALSE, state.nextTarget());
    }

    @Test
    public void eachConfirmedGearOutsideParkReleasesTheWipersOnce() {
        for (int gear : NON_PARK_GEARS) {
            ColdWiperState state = new ColdWiperState(true, false);
            state.setEnabled(true);
            state.onGear(PARK);
            state.onGear(gear, false);
            state.reconcile(true, state.positionUncertain());
            assertNull(state.nextTarget());

            state.onGear(gear, true);
            assertEquals("gear " + gear, Boolean.FALSE, state.nextTarget());
            assertTrue(state.canSend(false));
            state.onSent(false);
            state.onGear(gear, true);
            assertNull(state.nextTarget());
            state.onGear(DRIVE, true);
            assertNull(state.nextTarget());
            assertFalse(state.canSend(false));
        }
    }

    @Test
    public void reverseCancelsQueuedRaiseAndStillLowersIfRaiseAlreadyCrossedTheSendBoundary() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onGear(PARK);
        state.onDoor(1);
        assertTrue(state.canSend(true));
        state.onRequested(true);
        state.onGear(REVERSE);
        assertFalse(state.canSend(true));
        assertNull(state.nextTarget());
        state.onSent(true);
        assertEquals(Boolean.FALSE, state.nextTarget());
        assertTrue(state.canSend(false));
    }

    @Test
    public void returningToParkKeepsTheReleaseRequestedDuringAnInFlightRaise() {
        for (int gear : NON_PARK_GEARS) {
            ColdWiperState state = new ColdWiperState(false, false);
            state.setEnabled(true);
            state.onGear(PARK);
            state.onDoor(1);
            state.onRequested(true);
            state.onFrameAttempted();
            state.onGear(gear);
            state.onGear(PARK);
            state.onSent(true);
            assertEquals("gear " + gear, Boolean.FALSE, state.nextTarget());
        }
    }

    @Test
    public void reverseReleaseWaitsForDoorCloseBeforeAnotherParkedActivation() {
        ColdWiperState state = new ColdWiperState(true, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onGear(REVERSE);
        state.onSent(false);
        state.onGear(PARK);
        state.reconcile(false, state.positionUncertain());
        state.onDoor(1);
        assertTrue(state.waitForDoorClose());
        assertNull(state.nextTarget());
        ColdWiperState restarted = new ColdWiperState(false, state.waitForDoorClose());
        restarted.setEnabled(true);
        restarted.onDoor(1);
        assertNull(restarted.nextTarget());
        restarted.onDoor(0);
        restarted.onDoor(1);
        assertEquals(Boolean.TRUE, restarted.nextTarget());
    }

    @Test
    public void anAcceptedRaiseRemainsLatchedWhenPreferenceChangesDuringTheSend() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.setEnabled(false);
        assertFalse(state.canSend(true));
        state.onSent(true);
        state.reconcile(true, state.positionUncertain());
        assertNull(state.nextTarget());
        state.onGear(DRIVE);
        assertEquals(Boolean.FALSE, state.nextTarget());
    }

    @Test
    public void attemptedFailureStaysBlockedInParkAndOnCachedGearsAcrossRestart() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onRequested(true);
        state.onFrameAttempted();
        state.onFailed();
        assertTrue(state.positionUncertain());
        state.onGear(PARK, true);
        assertTrue(state.positionUncertain());
        for (int gear : NON_PARK_GEARS) {
            state.reconcile(false, state.positionUncertain());
            state.onGear(gear, false);
            state.onDoor(0);
            state.onDoor(1);
            state.setEnabled(false);
            state.setEnabled(true);
            assertNull(state.nextTarget());
            assertFalse(state.canSend(true));
            assertFalse(state.canSend(false));
        }
        state.reconcile(true, state.positionUncertain());
        assertTrue(state.positionUncertain());
        assertNull(state.nextTarget());
        state.reconcile(false, state.positionUncertain());
        ColdWiperState restarted = new ColdWiperState(
                false, state.waitForDoorClose(), state.positionUncertain());
        restarted.setEnabled(true);
        restarted.onDoor(1);
        restarted.onGear(REVERSE, false);
        restarted.reconcile(false, restarted.positionUncertain());
        assertNull(restarted.nextTarget());
        assertFalse(restarted.canSend(true));
        assertFalse(restarted.canSend(false));
    }

    @Test
    public void freshDriveClearsUncertaintyAndAllowsTheNextParkedDoorOpening() {
        ColdWiperState state = new ColdWiperState(false, false, true);
        state.setEnabled(true);
        state.onDoor(0);
        state.onGear(PARK, true);
        assertNull(state.nextTarget());

        state.onGear(DRIVE, false);
        assertTrue(state.positionUncertain());
        state.onGear(DRIVE, true);
        assertFalse(state.positionUncertain());
        assertNull(state.nextTarget());

        ColdWiperState restarted = new ColdWiperState(
                false, state.waitForDoorClose(), state.positionUncertain());
        restarted.setEnabled(true);
        restarted.onDoor(0);
        restarted.onGear(PARK, true);
        restarted.onDoor(1);
        assertEquals(Boolean.TRUE, restarted.nextTarget());
        assertTrue(restarted.canSend(true));
    }

    @Test
    public void freshNeutralClearsUncertaintyForAnActivePositionAndWaitsForDoorClose() {
        ColdWiperState state = new ColdWiperState(true, false, true);
        state.setEnabled(true);
        state.onDoor(1);
        state.onGear(NEUTRAL, true);
        assertFalse(state.positionUncertain());
        assertEquals(Boolean.FALSE, state.nextTarget());
        assertTrue(state.canSend(false));
        state.onSent(false);
        state.onGear(PARK, true);
        assertNull(state.nextTarget());
        state.onDoor(0);
        state.onDoor(1);
        assertEquals(Boolean.TRUE, state.nextTarget());
    }

    @Test
    public void freshDriveDoesNotClearAnUnfinishedSendReceipt() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onRequested(true);
        state.onFrameAttempted();
        state.onGear(DRIVE, true);
        assertTrue(state.positionUncertain());
        assertNull(state.nextTarget());
        state.onFailed();
        assertTrue(state.positionUncertain());
        state.onGear(DRIVE, true);
        assertFalse(state.positionUncertain());
    }

    @Test
    public void suppressedFrameCanRetryAndAcceptedResultClearsItsPendingUncertainty() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onRequested(true);
        state.onFailed();
        assertFalse(state.positionUncertain());
        assertEquals(Boolean.TRUE, state.nextTarget());
        assertTrue(state.canSend(true));
        state.onRequested(true);
        state.onFrameAttempted();
        assertNull(state.nextTarget());
        state.onSent(true);
        assertFalse(state.positionUncertain());
        state.onGear(DRIVE, true);
        assertEquals(Boolean.FALSE, state.nextTarget());
        state.onRequested(false);
        state.onFrameAttempted();
        state.onSent(false);
        assertFalse(state.positionUncertain());
        assertNull(state.nextTarget());
    }

    @Test
    public void recreatedOwnerAdoptsSuccessfulOldCommandAndReleasesOnItsFreshDrive() {
        ColdWiperState oldOwner = new ColdWiperState(false, false);
        oldOwner.setEnabled(true);
        oldOwner.onDoor(1);
        oldOwner.onRequested(true);
        oldOwner.onFrameAttempted();
        ColdWiperState recreated = new ColdWiperState(false, false, oldOwner.positionUncertain());
        recreated.setEnabled(true);
        recreated.onGear(DRIVE, true);
        assertNull(recreated.nextTarget());
        oldOwner.onSent(true);
        recreated.reconcile(true, oldOwner.positionUncertain());
        assertFalse(recreated.positionUncertain());
        assertEquals(Boolean.FALSE, recreated.nextTarget());
        assertTrue(recreated.canSend(false));
    }

    @Test
    public void recreatedOwnerRemainsBlockedAfterTheOldCommandsAttemptedFailure() {
        ColdWiperState oldOwner = new ColdWiperState(false, false);
        oldOwner.setEnabled(true);
        oldOwner.onDoor(1);
        oldOwner.onRequested(true);
        oldOwner.onFrameAttempted();
        ColdWiperState recreated = new ColdWiperState(false, false, oldOwner.positionUncertain());
        recreated.setEnabled(true);
        recreated.onGear(DRIVE, true);
        oldOwner.onFailed();
        recreated.reconcile(false, oldOwner.positionUncertain());
        assertTrue(recreated.positionUncertain());
        assertNull(recreated.nextTarget());
        assertFalse(recreated.canSend(false));
        assertFalse(recreated.canSend(true));
    }

    @Test
    public void recreatedOwnerCanRetryAfterReceiptWasClearedWithoutAFrameAttempt() {
        ColdWiperState recreated = new ColdWiperState(false, false, true);
        recreated.setEnabled(true);
        recreated.onGear(PARK, true);
        recreated.onDoor(1);
        assertNull(recreated.nextTarget());
        recreated.reconcile(false, false);
        assertFalse(recreated.positionUncertain());
        assertEquals(Boolean.TRUE, recreated.nextTarget());
        assertTrue(recreated.canSend(true));
    }

    @Test
    public void freshRaiseRequiresParkOrUnknownGearAndFailuresDoNotClaimSuccess() {
        for (int gear : NON_PARK_GEARS) {
            ColdWiperState state = new ColdWiperState(false, false);
            state.setEnabled(true);
            state.onGear(gear);
            state.onDoor(1);
            assertNull(state.nextTarget());
        }
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onGear(PARK);
        state.onDoor(1);
        assertEquals(Boolean.TRUE, state.nextTarget());
        state.reconcile(false, state.positionUncertain());
        assertEquals(Boolean.TRUE, state.nextTarget());
        state.onSent(true);
        assertNull(state.nextTarget());
    }
}
