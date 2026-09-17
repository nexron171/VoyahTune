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
    public void sleepCancelsBothQueuedRestoreEventsAndWakeActions() {
        ApplyEngine.RestoreRunState state = new ApplyEngine.RestoreRunState();
        long wake = state.currentGeneration();
        long doorEpoch = state.currentRestoreEpoch();
        state.activate(wake);
        long driveEpoch = state.currentRestoreEpoch();
        assertTrue(state.isRestoreCurrent(wake, doorEpoch));
        assertTrue(state.isRestoreCurrent(wake, driveEpoch));
        state.cancelAndAdvance();
        assertFalse(state.isRestoreCurrent(wake, doorEpoch));
        assertFalse(state.isRestoreCurrent(wake, driveEpoch));
        assertFalse(state.isActionAllowed(wake));
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
        long wake = state.currentGeneration();
        state.activate(wake);
        long oldRestore = state.currentRestoreEpoch();
        long manualRestore = state.cancelRestoreAndAdvance();
        assertFalse(state.isRestoreCurrent(wake, oldRestore));
        assertTrue(state.isRestoreCurrent(wake, manualRestore));
        assertTrue(state.isActionAllowed(wake));
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
    public void guardSuppressedFrameDoesNotReportAnAttempt() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        boolean result = CanSender.runGuardedSend(allowed::get, attempts::incrementAndGet,
                () -> {
                    // The outer operation was admitted, but the final per-frame guard changed
                    // before the emulated/native transaction boundary.
                    allowed.set(false);
                    return CanSender.beginFrameAttemptForCurrentGuard();
                });

        assertFalse(result);
        assertEquals(0, attempts.get());
    }

    @Test
    public void frameAttemptIsReportedAfterItsFinalGuard() {
        AtomicInteger attempts = new AtomicInteger();
        boolean result = CanSender.runGuardedSend(() -> true,
                attempts::incrementAndGet,
                CanSender::beginFrameAttemptForCurrentGuard);

        assertTrue(result);
        assertEquals(1, attempts.get());
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
