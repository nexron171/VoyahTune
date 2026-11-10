package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ColdWiperStateTest {
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
        for (int gear : new int[] {-1, 0, 1, 2, 4, 99}) {
            state.onGear(gear);
            state.reconcile(true, state.positionUncertain());
            assertNull(state.nextTarget());
        }
        state.onGear(3);
        assertEquals(Boolean.FALSE, state.nextTarget());
    }

    @Test
    public void cachedDriveCannotReleaseTheLatchButAFreshDriveConfirmationCan() {
        ColdWiperState state = new ColdWiperState(true, false);
        state.setEnabled(true);
        state.onGear(3, false);
        state.reconcile(true, state.positionUncertain());
        assertNull(state.nextTarget());
        state.onGear(3, true);
        assertEquals(Boolean.FALSE, state.nextTarget());
    }

    @Test
    public void driveCancelsQueuedRaiseAndStillLowersIfRaiseAlreadyCrossedTheSendBoundary() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        assertTrue(state.canSend(true));
        state.onGear(3);
        assertFalse(state.canSend(true));
        assertNull(state.nextTarget());
        state.onSent(true);
        assertEquals(Boolean.FALSE, state.nextTarget());
        assertTrue(state.canSend(false));
    }

    @Test
    public void aConfirmedDriveRemainsTheReleaseCauseUntilAnInFlightRaiseCompletes() {
        for (int followingGear : new int[] {0, 1, 2, 4}) {
            ColdWiperState state = new ColdWiperState(false, false);
            state.setEnabled(true);
            state.onDoor(1);
            state.onRequested(true);
            state.onGear(3);
            state.onGear(followingGear);
            state.onSent(true);
            assertEquals(Boolean.FALSE, state.nextTarget());
        }
    }

    @Test
    public void driveReleaseWaitsForDoorCloseBeforeAnotherParkedActivation() {
        ColdWiperState state = new ColdWiperState(true, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onGear(3);
        state.onSent(false);
        state.onGear(0);
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
        state.onGear(3);
        assertEquals(Boolean.FALSE, state.nextTarget());
    }

    @Test
    public void attemptedFailureBlocksEveryLaterAutomaticEventAndSurvivesRestart() {
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onDoor(1);
        state.onRequested(true);
        state.onFrameAttempted();
        state.onFailed();
        assertTrue(state.positionUncertain());
        for (int gear : new int[] {0, 3, 1, 0, 3}) {
            state.reconcile(false, state.positionUncertain());
            state.onGear(gear, true);
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
        restarted.onGear(3, true);
        restarted.reconcile(false, restarted.positionUncertain());
        assertNull(restarted.nextTarget());
        assertFalse(restarted.canSend(true));
        assertFalse(restarted.canSend(false));
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
        state.onGear(3, true);
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
        recreated.onGear(3, true);
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
        recreated.onGear(3, true);
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
        recreated.onGear(0, true);
        recreated.onDoor(1);
        assertNull(recreated.nextTarget());
        recreated.reconcile(false, false);
        assertFalse(recreated.positionUncertain());
        assertEquals(Boolean.TRUE, recreated.nextTarget());
        assertTrue(recreated.canSend(true));
    }

    @Test
    public void freshRaiseRequiresParkOrUnknownGearAndFailuresDoNotClaimSuccess() {
        for (int gear : new int[] {1, 2, 3, 4}) {
            ColdWiperState state = new ColdWiperState(false, false);
            state.setEnabled(true);
            state.onGear(gear);
            state.onDoor(1);
            assertNull(state.nextTarget());
        }
        ColdWiperState state = new ColdWiperState(false, false);
        state.setEnabled(true);
        state.onGear(0);
        state.onDoor(1);
        assertEquals(Boolean.TRUE, state.nextTarget());
        state.reconcile(false, state.positionUncertain());
        assertEquals(Boolean.TRUE, state.nextTarget());
        state.onSent(true);
        assertNull(state.nextTarget());
    }
}
