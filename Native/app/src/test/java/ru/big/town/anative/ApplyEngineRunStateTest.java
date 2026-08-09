package ru.big.town.anative;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApplyEngineRunStateTest {

    @Test
    public void cancelledCycleCannotRecordCompletionOrCoverNextWake() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long sleepingWake = state.currentGeneration();
        long sleepingRestore = state.currentRestoreEpoch();
        state.registerTrigger(sleepingWake, sleepingRestore);

        long nextWake = state.cancelAndAdvance();
        long nextRestore = state.currentRestoreEpoch();
        long nextWakeTrigger = state.registerTrigger(nextWake, nextRestore);

        // The physical generation check is also used by queued wake actions.
        assertFalse(state.isCurrent(sleepingWake));
        assertFalse(state.isRestoreCurrent(sleepingWake, sleepingRestore));
        assertFalse(state.completeCycle(
                sleepingWake, sleepingRestore, true, 10_000L));
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(nextWake, nextRestore, nextWakeTrigger).kind);
    }

    @Test
    public void frozenGenerationRejectsAutomatedActionsUntilWakeActivation() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long firstWake = state.currentGeneration();
        assertTrue(state.activate(firstWake));
        assertTrue(state.isActionAllowed(firstWake));

        long sleeping = state.cancelAndAdvance();
        assertFalse(state.isActionAllowed(sleeping));
        assertTrue(state.activate(sleeping));
        assertTrue(state.isActionAllowed(sleeping));
    }

    @Test
    public void explicitCommandSupersedesRestoreWithoutFreezingPhysicalWake() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long wakeGeneration = state.currentGeneration();
        assertTrue(state.activate(wakeGeneration));
        long staleRestore = state.currentRestoreEpoch();
        long staleTrigger = state.registerTrigger(wakeGeneration, staleRestore);
        long staleBoundary = state.coverageBoundary(wakeGeneration, staleRestore);
        assertTrue(state.markCanRestoreComplete(
                wakeGeneration, staleRestore, staleBoundary));

        long currentRestore = state.cancelRestoreAndAdvance();

        // postWakeAction remains tied only to physical sleep/wake.
        assertTrue(state.isActionAllowed(wakeGeneration));
        // A running cycle and a queued debounce captured before the explicit command are stale.
        assertFalse(state.isRestoreCurrent(wakeGeneration, staleRestore));
        assertEquals(-1L, state.registerTrigger(wakeGeneration, staleRestore));
        assertFalse(state.completeCycle(
                wakeGeneration, staleRestore, true, 10_000L));

        long currentTrigger = state.registerTrigger(wakeGeneration, currentRestore);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(wakeGeneration, currentRestore, currentTrigger).kind);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(wakeGeneration, currentRestore, staleTrigger).kind);
    }

    @Test
    public void manualRestoreCanCompleteInNewEpochOfSamePhysicalWake() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long wakeGeneration = state.currentGeneration();
        assertTrue(state.activate(wakeGeneration));
        long staleRestore = state.currentRestoreEpoch();

        long manualRestore = state.cancelRestoreAndAdvance();
        long trigger = state.registerTrigger(wakeGeneration, manualRestore);
        long boundary = state.coverageBoundary(wakeGeneration, manualRestore);
        assertTrue(state.markCanRestoreComplete(
                wakeGeneration, manualRestore, boundary));
        assertTrue(state.completeCycle(
                wakeGeneration, manualRestore, true, 20_000L));

        assertFalse(state.isRestoreCurrent(wakeGeneration, staleRestore));
        assertTrue(state.isActionAllowed(wakeGeneration));
        assertEquals(ApplyEngine.RestoreRunState.Coverage.SUCCESS,
                state.coverage(wakeGeneration, manualRestore, trigger).kind);
    }

    @Test
    public void cancelClearsSuccessfulCoverageFromPreviousWake() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long firstWake = state.currentGeneration();
        long firstRestore = state.currentRestoreEpoch();
        long firstWakeTrigger = state.registerTrigger(firstWake, firstRestore);
        assertTrue(state.markCanRestoreComplete(
                firstWake, firstRestore,
                state.coverageBoundary(firstWake, firstRestore)));
        assertTrue(state.completeCycle(firstWake, firstRestore, true, 10_000L));
        assertEquals(ApplyEngine.RestoreRunState.Coverage.SUCCESS,
                state.coverage(firstWake, firstRestore, firstWakeTrigger).kind);

        long secondWake = state.cancelAndAdvance();
        long secondRestore = state.currentRestoreEpoch();
        long secondWakeTrigger = state.registerTrigger(secondWake, secondRestore);

        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(secondWake, secondRestore, secondWakeTrigger).kind);
    }

    @Test
    public void failedCycleIsCoveredButNeverReportedAsSuccessfulRestore() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long generation = state.currentGeneration();
        long restoreEpoch = state.currentRestoreEpoch();
        long trigger = state.registerTrigger(generation, restoreEpoch);

        assertTrue(state.completeCycle(generation, restoreEpoch, false, 10_000L));

        ApplyEngine.RestoreRunState.Coverage coverage =
                state.coverage(generation, restoreEpoch, trigger);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.FAILED, coverage.kind);
        assertEquals(10_000L, coverage.completedAt);

        // A genuinely later reconnect is not swallowed by that failed 120-second window.
        long lateTrigger = state.registerTrigger(generation, restoreEpoch);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(generation, restoreEpoch, lateTrigger).kind);
    }

    @Test
    public void triggerAfterCompletionIsNotCoveredEvenAtSameTimestamp() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long generation = state.currentGeneration();
        long restoreEpoch = state.currentRestoreEpoch();
        long oldTrigger = state.registerTrigger(generation, restoreEpoch);
        assertTrue(state.markCanRestoreComplete(
                generation, restoreEpoch,
                state.coverageBoundary(generation, restoreEpoch)));
        assertTrue(state.completeCycle(generation, restoreEpoch, true, 10_000L));
        assertEquals(ApplyEngine.RestoreRunState.Coverage.SUCCESS,
                state.coverage(generation, restoreEpoch, oldTrigger).kind);

        long laterTrigger = state.registerTrigger(generation, restoreEpoch);

        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(generation, restoreEpoch, laterTrigger).kind);
    }

    @Test
    public void triggerDuringCustomTailIsNotCoveredByEarlierModeCan() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long generation = state.currentGeneration();
        long restoreEpoch = state.currentRestoreEpoch();
        long triggerBeforeCan = state.registerTrigger(generation, restoreEpoch);
        assertTrue(state.markCanRestoreComplete(
                generation, restoreEpoch,
                state.coverageBoundary(generation, restoreEpoch)));

        long triggerDuringCustomTail = state.registerTrigger(generation, restoreEpoch);
        assertTrue(state.completeCycle(generation, restoreEpoch, true, 10_000L));

        assertEquals(ApplyEngine.RestoreRunState.Coverage.SUCCESS,
                state.coverage(generation, restoreEpoch, triggerBeforeCan).kind);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(generation, restoreEpoch, triggerDuringCustomTail).kind);
    }

    @Test
    public void triggerArrivingDuringLastCanPassIsNotCovered() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long generation = state.currentGeneration();
        long restoreEpoch = state.currentRestoreEpoch();
        long triggerBeforePass = state.registerTrigger(generation, restoreEpoch);
        long passBoundary = state.coverageBoundary(generation, restoreEpoch);

        // Models a trigger racing with runCmds(): it is ordered after the pass boundary even if the
        // native send completes later in wall-clock time.
        long triggerDuringPass = state.registerTrigger(generation, restoreEpoch);
        assertTrue(state.markCanRestoreComplete(generation, restoreEpoch, passBoundary));
        assertTrue(state.completeCycle(generation, restoreEpoch, true, 10_000L));

        assertEquals(ApplyEngine.RestoreRunState.Coverage.SUCCESS,
                state.coverage(generation, restoreEpoch, triggerBeforePass).kind);
        assertEquals(ApplyEngine.RestoreRunState.Coverage.NONE,
                state.coverage(generation, restoreEpoch, triggerDuringPass).kind);
    }

    @Test
    public void emptyOrMalformedCustomFramesAreSkipped() {
        assertEquals(0, ApplyEngine.validCanFrames(null).length);
        assertEquals(0, ApplyEngine.validCanFrames(new byte[][]{{}}).length);
        assertEquals(0, ApplyEngine.validCanFrames(
                new byte[][]{new byte[9], new byte[11]}).length);
    }

    @Test
    public void onlyTenByteCustomFramesAreSent() {
        byte[] firstValid = new byte[10];
        byte[] secondValid = new byte[10];

        byte[][] filtered = ApplyEngine.validCanFrames(
                new byte[][]{new byte[0], firstValid, null, new byte[11], secondValid});

        assertEquals(2, filtered.length);
        assertTrue(filtered[0] == firstValid);
        assertTrue(filtered[1] == secondValid);
    }

    @Test
    public void cancelledSendGuardDoesNotEnterCanOperation() {
        AtomicBoolean entered = new AtomicBoolean(false);

        boolean result = CanSender.runGuardedSend(() -> false, () -> {
            entered.set(true);
            return true;
        });

        assertFalse(result);
        assertFalse(entered.get());
    }

    @Test
    public void wakeActionExceptionDeliversFailedExactlyOnce() {
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<ApplyEngine.WakeActionResult> terminal = new AtomicReference<>();

        ApplyEngine.runWakeActionExactlyOnce("exception", () -> true, () -> {
            entered.incrementAndGet();
            throw new IllegalStateException("boom");
        }, result -> {
            completions.incrementAndGet();
            terminal.set(result);
        });

        assertEquals(1, entered.get());
        assertEquals(1, completions.get());
        assertEquals(ApplyEngine.WakeActionResult.FAILED, terminal.get());
    }

    @Test
    public void cancellationDuringWakeActionDeliversSkippedExactlyOnce() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<ApplyEngine.WakeActionResult> terminal = new AtomicReference<>();

        ApplyEngine.runWakeActionExactlyOnce("cancel", allowed::get, () -> {
            allowed.set(false);
            return false;
        }, result -> {
            completions.incrementAndGet();
            terminal.set(result);
        });

        assertEquals(1, completions.get());
        assertEquals(ApplyEngine.WakeActionResult.SKIPPED, terminal.get());
    }

    @Test
    public void successfulWakeActionIsNotReclassifiedWhenSleepRacesCompletion() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<ApplyEngine.WakeActionResult> terminal = new AtomicReference<>();

        ApplyEngine.runWakeActionExactlyOnce("success", allowed::get, () -> {
            allowed.set(false);
            return true;
        }, result -> {
            completions.incrementAndGet();
            terminal.set(result);
        });

        assertEquals(1, completions.get());
        assertEquals(ApplyEngine.WakeActionResult.SUCCESS, terminal.get());
    }

    @Test
    public void throwingTerminalCallbackIsStillInvokedOnlyOnce() {
        AtomicInteger completions = new AtomicInteger();

        ApplyEngine.runWakeActionExactlyOnce(
                "terminal exception", () -> true, () -> true, result -> {
                    completions.incrementAndGet();
                    throw new IllegalStateException("callback boom");
                });

        assertEquals(1, completions.get());
    }
}
