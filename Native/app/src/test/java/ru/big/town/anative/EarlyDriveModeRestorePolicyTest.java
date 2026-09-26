package ru.big.town.anative;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static ru.big.town.anative.EarlyDriveModeRestorePolicy.Decision.*;

public class EarlyDriveModeRestorePolicyTest {
    private EarlyDriveModeRestorePolicy started(String target) {
        EarlyDriveModeRestorePolicy policy = new EarlyDriveModeRestorePolicy();
        assertTrue(policy.activate(4));
        assertTrue(policy.beginCheck(4, 100));
        assertTrue(policy.acceptTarget(4, 100, true, target));
        return policy;
    }

    @Test public void ecoIsTheOnlyDifferentModeWeRepair() {
        String[] modes = {"ECO", "COMFORT", "SPORT", "OUTING", "INDIVIDUAL", "SNOW"};
        for (int i = 0; i < modes.length; i++) {
            EarlyDriveModeRestorePolicy policy = started(modes[i]);
            assertEquals(MATCH, policy.observe(4, 100, i + 1));
            assertEquals(i == 0 ? MATCH : RESTORE_ECO, policy.observe(4, 1_100, 1));
        }
    }

    @Test public void anotherValidModeStopsRatherThanOverwritingTheDriver() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertEquals(STOP, policy.observe(4, 1_100, 2));
        assertEquals(STOP, policy.observe(4, 2_100, 1));
        assertFalse(policy.activate(4)); // A later SCREEN_ON/CanBus connection cannot rearm it.
    }

    @Test public void ecoTargetStillStopsOnAnotherSelection() {
        assertEquals(STOP, started("ECO").observe(4, 100, 3));
    }

    @Test public void unavailableAndInvalidReadsAreSkippedWithinTheOriginalWindow() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        for (Integer value : new Integer[]{null, -1, 0, 7, 255}) {
            assertEquals(WAIT, policy.observe(4, 20_100, value));
        }
        assertEquals(RESTORE_ECO, policy.observe(4, 29_100, 1));
        assertEquals(STOP, policy.observe(4, 30_100, 1));
    }

    @Test public void matchingAndRepeatedEcoNeverExtendThirtySeconds() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertEquals(MATCH, policy.observe(4, 100, 3));
        assertEquals(RESTORE_ECO, policy.observe(4, 15_100, 1));
        assertEquals(MATCH, policy.observe(4, 20_100, 3));
        assertEquals(RESTORE_ECO, policy.observe(4, 30_099, 1));
        assertFalse(policy.beginCheck(4, 30_100));
        assertFalse(policy.activate(4));
    }

    @Test public void clockStartsAtFirstCheckNotWakeEnqueueOrFirstWrite() {
        EarlyDriveModeRestorePolicy policy = new EarlyDriveModeRestorePolicy();
        assertTrue(policy.activate(0));
        assertTrue(policy.beginCheck(0, 60_000));
        assertTrue(policy.isActive(0, 89_999));
        assertFalse(policy.isActive(0, 90_000));
    }

    @Test public void missingSettingsDoNotLeaveAnUnboundedPoll() {
        EarlyDriveModeRestorePolicy policy = new EarlyDriveModeRestorePolicy();
        policy.activate(0);
        policy.beginCheck(0, 0);
        assertEquals(WAIT, policy.observe(0, 29_000, 1));
        assertEquals(STOP, policy.observe(0, 30_000, 1));
    }

    @Test public void doorOrDriveBeforeStartupConsumesThatWake() {
        EarlyDriveModeRestorePolicy policy = new EarlyDriveModeRestorePolicy();
        policy.stop(0);
        assertFalse(policy.activate(0));
        assertFalse(policy.beginCheck(0, 100));
        assertTrue(policy.activate(1));
        assertTrue(policy.beginCheck(1, 200));
    }

    @Test public void sleepCancelsReadResultsAndOnlyANewWakeRearms() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        policy.stop(4);
        assertEquals(STOP, policy.observe(4, 200, 1));
        assertFalse(policy.activate(4));
        assertTrue(policy.activate(5));
        assertTrue(policy.beginCheck(5, 300));
        assertTrue(policy.acceptTarget(5, 300, true, "COMFORT"));
        assertEquals(STOP, policy.observe(4, 999_999, 1));
        assertFalse(policy.stop(4)); // Late cleanup must also leave the newer worker queued.
        assertEquals(RESTORE_ECO, policy.observe(5, 400, 1));
    }

    @Test public void duplicateWakeSignalsDoNotResetTheDeadline() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertFalse(policy.activate(4));
        assertFalse(policy.activate(3));
        assertTrue(policy.beginCheck(4, 25_100));
        assertFalse(policy.isActive(4, 30_100));
    }

    @Test public void disabledInvalidOrChangedSettingsCancelTheRun() {
        assertFalse(started("SPORT").acceptTarget(4, 200, false, "SPORT"));
        assertFalse(started("SPORT").acceptTarget(4, 200, true, null));
        assertFalse(started("SPORT").acceptTarget(4, 200, true, "unsupported"));
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertFalse(policy.acceptTarget(4, 200, true, "COMFORT"));
        assertFalse(policy.acceptTarget(4, 300, true, "SPORT"));
        assertEquals(STOP, policy.observe(4, 400, 1));
    }

    @Test public void failedSendIsSkippedAndNextEcoCheckStillRetries() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertEquals(RESTORE_ECO, policy.observe(4, 100, 1));
        assertFalse(CanSender.runGuardedSend(() -> policy.isActive(4, 100), () -> false));
        assertEquals(RESTORE_ECO, policy.observe(4, 1_100, 1));
        assertEquals(STOP, policy.observe(4, 30_100, 1));
    }

    @Test public void cancellationBetweenReadAndSendPreventsTheWrite() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertEquals(RESTORE_ECO, policy.observe(4, 100, 1));
        policy.stop(4); // Door, D or an explicit user command arrived while reading.
        AtomicBoolean sent = new AtomicBoolean();
        assertFalse(CanSender.runGuardedSend(() -> policy.isActive(4, 200), () -> {
            sent.set(true);
            return true;
        }));
        assertFalse(sent.get());
    }

    @Test public void timeoutDuringProfilePreparationPreventsTheFinalTransaction() {
        EarlyDriveModeRestorePolicy policy = started("INDIVIDUAL");
        AtomicLong now = new AtomicLong(29_100);
        assertFalse(CanSender.runGuardedSend(() -> policy.isActive(4, now.get()), () -> {
            now.set(30_100); // Provider/connection/transaction-lock wait consumed the remainder.
            return CanSender.beginFrameAttemptForCurrentGuard();
        }));
    }

    @Test public void pollingKeepsOneSecondCadenceWithoutOverlappingOrPassingDeadline() {
        EarlyDriveModeRestorePolicy policy = started("SPORT");
        assertEquals(900, policy.nextDelay(4, 200, 100));
        assertEquals(1, policy.nextDelay(4, 1_600, 100));
        assertEquals(100, policy.nextDelay(4, 30_000, 29_900));
        assertEquals(-1, policy.nextDelay(4, 30_100, 29_900));
    }
}
