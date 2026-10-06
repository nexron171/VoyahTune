package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class TripSessionTest {
    @Test public void manualStopSavesShortTripsOnlyWhenHistoryIsEnabled() {
        assertTrue(TripSession.shouldSaveHistory(true, true, 0));
        assertTrue(TripSession.shouldSaveHistory(true, true, 1000));
        assertFalse(TripSession.shouldSaveHistory(false, true, 600000));
        assertFalse(TripSession.shouldSaveHistory(true, false, TripSession.MIN_HISTORY_MS - 1));
        assertTrue(TripSession.shouldSaveHistory(true, false, TripSession.MIN_HISTORY_MS));
        assertFalse(TripSession.shouldSaveHistory(true, true, -1));
    }
    @Test public void stopWaitsForTenMetersAndStartsANewClockWithoutCountingTheWait() {
        TripSession previous = new TripSession(); previous.gear(true, 1000, 100);
        previous.gear(false, 7000, 6100); previous.waitForMovement(1000000, 2);
        TripSession next = previous.afterFinish();
        assertFalse(next.active); assertFalse(next.inDrive); assertEquals(0, next.duration(69100));
        assertFalse(next.movementRestart(1000000, 2));
        assertFalse(next.movementRestart(1000009.999, 2));
        assertTrue(next.movementRestart(1000010, 2));
        next.gear(true, 70000, 69100);
        assertEquals(70000, next.startWall); assertEquals(1000, next.duration(70100));
        assertEquals(6000, previous.duration(70100));
        assertFalse(next.waitingForMovement); assertFalse(next.movementRestart(1000020, 2));
    }
    @Test public void stoppedTripIgnoresOldDriveAndSnapshotsButAcceptsANewLiveDriveEdge() {
        TripSession s = new TripSession(); s.waitForMovement(1000000, 2);
        assertFalse(s.allowsDriveStart(false, 0));
        assertFalse(s.allowsDriveStart(false, 3));
        assertFalse(s.allowsDriveStart(true, 3));
        assertFalse(s.allowsDriveStart(true, -1));
        assertTrue(s.allowsDriveStart(true, 0));
        s.gear(true, 1000, 100);
        assertTrue(s.active); assertFalse(s.waitingForMovement);
    }
    @Test public void idleMovementGuardSurvivesSameBootAndRebootWithoutUsingAnOldClock() {
        TripSession s = new TripSession(); s.bootCount = 7; s.gear(true, 1000, 100);
        s.gear(false, 7000, 6100); s.waitForMovement(1000000, 2); s.checkpoint(7000, 6100);
        for (int boot : new int[]{7, 8}) {
            TripSession idle = s.afterFinish(); idle.recover(boot); idle.checkpoint(90000, 10000);
            assertFalse(idle.active); assertFalse(idle.persistedDrive()); assertEquals(0, idle.duration(11000));
            assertFalse(idle.allowsDriveStart(false, -1));
            assertFalse(idle.movementRestart(1000009, 2)); assertTrue(idle.movementRestart(1000010, 2));
            idle.gear(true, 91000, 11000);
            assertEquals(boot, idle.bootCount); assertEquals(1000, idle.duration(12000));
        }
    }
    @Test public void missingStopOdometerNeedsAFreshAnchorBeforeMeasuringMovement() {
        TripSession s = new TripSession(); s.waitForMovement(Double.NaN, 0);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0})
            assertFalse(s.movementRestart(invalid, 2));
        assertFalse(s.movementRestart(1000000, 0));
        assertFalse(s.movementRestart(1000000, 2));
        assertFalse(s.movementRestart(1000009, 2)); assertTrue(s.movementRestart(1000010, 2));
    }
    @Test public void changingOdometerSourcesDoesNotCompareUnrelatedRoundedValues() {
        TripSession s = new TripSession(); s.waitForMovement(1000000, 1);
        assertFalse(s.movementRestart(1000100, 2));
        assertFalse(s.movementRestart(1000109, 2)); assertTrue(s.movementRestart(1000110, 2));
        assertFalse(s.movementRestart(1001000, 1));
        assertFalse(s.movementRestart(1001009, 1)); assertTrue(s.movementRestart(1001010, 1));
    }
    @Test public void odometerRollbackReanchorsAndMissingSamplesCannotStartATrip() {
        TripSession s = new TripSession(); s.waitForMovement(1000000, 2);
        assertFalse(s.movementRestart(999900, 2));
        assertFalse(s.movementRestart(Double.NaN, 0));
        assertFalse(s.movementRestart(999900, 2)); assertFalse(s.movementRestart(999909, 2));
        assertTrue(s.movementRestart(999910, 2));
    }
    @Test public void aDoorOpeningCancelsTheManualMovementGuard() {
        TripSession s = new TripSession(); s.waitForMovement(1000000, 2);
        s.cancelMovementRestart();
        assertFalse(s.movementRestart(1001000, 2)); assertFalse(s.waitingForMovement);
        assertTrue(s.allowsDriveStart(false, -1));
    }
    @Test public void movementDoesNotSplitAnOrdinaryPausedTripOrStartFromAnUnarmedIdleState() {
        TripSession s = new TripSession(); assertFalse(s.movementRestart(1000010, 2));
        s.gear(true, 1000, 100); s.gear(false, 2000, 1100);
        assertFalse(s.movementRestart(1001000, 2)); assertTrue(s.active); assertFalse(s.inDrive);
        assertEquals(1000, s.startWall); assertEquals(1000, s.duration(9000));
    }
    @Test public void openDoorLevelAfterRestartNeedsARealClosedToOpenTransition() {
        assertFalse(TripSession.doorOpening(false, 0, 1));
        assertFalse(TripSession.doorOpening(true, -1, 1));
        assertFalse(TripSession.doorOpening(true, 1, 1));
        assertTrue(TripSession.doorOpening(true, 0, 1));
    }
    @Test public void drivePauseResumeKeepsOneStartAndOnlyCountsDrive() {
        TripSession s = new TripSession(); s.gear(true, 1000, 100);
        s.gear(false, 3000, 2100); assertEquals(2000, s.duration(9000));
        s.gear(true, 10000, 9100); assertEquals(1000, s.startWall); assertEquals(3000, s.duration(10100));
    }
    @Test public void serviceRestartInSameBootContinuesConfirmedDriveWithoutDoubleCounting() {
        TripSession s = new TripSession(); s.bootCount = 7; s.gear(true, 1000, 100);
        s.checkpoint(6000, 5100); s.recover(7); assertFalse(s.inDrive);
        s.gear(true, 7000, 6100); assertEquals(7000, s.duration(7100));
        s.checkpoint(8000, 7100); assertEquals(7000, s.duration(7100));
    }
    @Test public void rebootKeepsMeasuredTimeWithoutReusingElapsedClockOrCountingOfflineTime() {
        TripSession s = new TripSession(); s.bootCount = 7; s.gear(true, 1000, 100);
        s.checkpoint(6000, 5100); s.recover(8); s.gear(false, 8000, 100);
        assertTrue(s.active); assertFalse(s.inDrive); assertEquals(5000, s.duration(100));
        assertEquals(6000, s.pausedWall); s.gear(true, 9000, 1100); assertEquals(6000, s.duration(2100));
    }
    @Test public void missedDoorFinalizesOnlyOnDriveAfterMoreThanOneHourOfPause() {
        TripSession s = new TripSession(); s.gear(true, 1000, 100); s.gear(false, 2000, 1100);
        assertFalse(s.expiresOnDrive(2000 + TripSession.PAUSE_TIMEOUT_MS));
        assertTrue(s.expiresOnDrive(2001 + TripSession.PAUSE_TIMEOUT_MS));
        s.gear(true, 3000, 2100); assertFalse(s.expiresOnDrive(9_000_000));
    }
    @Test public void repeatedParkingSnapshotAndClockRollbackDoNotMovePauseDeadline() {
        TripSession s = new TripSession(); s.gear(true, 1000, 100); s.gear(false, 2000, 1100);
        s.gear(false, 3_000_000, 2100); assertEquals(2000, s.pausedWall);
        assertFalse(s.expiresOnDrive(1000)); s.recover(10); assertEquals(2000, s.pausedWall);
    }
    @Test public void unknownBootIdentityCannotResumeAnOldElapsedClock() {
        TripSession s = new TripSession(); s.bootCount = -1; s.gear(true, 1000, 100);
        s.checkpoint(2000, 1100); s.recover(-1); s.gear(true, 10000, 9100);
        assertEquals(1000, s.duration(9100));
    }
    @Test public void checkpointWhileAwaitingGearRetainsTheOriginalClockAndBootIdentity() {
        TripSession s = new TripSession(); s.bootCount = 7; s.gear(true, 1000, 100);
        s.checkpoint(6000, 5100); s.recover(7); s.checkpoint(20000, 19100);
        assertEquals(5100, s.checkpointElapsed); assertTrue(s.persistedDrive());
        s.gear(true, 21000, 20100); assertEquals(20000, s.duration(20100));
        s.checkpoint(22000, 21100); s.recover(8); s.checkpoint(23000, 100);
        assertEquals(7, s.bootCount); assertEquals(21100, s.checkpointElapsed);
    }
}
