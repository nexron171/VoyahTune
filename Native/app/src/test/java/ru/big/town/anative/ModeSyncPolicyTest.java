package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModeSyncPolicyTest {

    @Test
    public void wakeDefaultRequestsCorrectionAndIsNeverAccepted() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 1_000L));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(false, "ECO", 1_100L));
        // One full restore corrects both enabled modes, so the cooldown is deliberately shared.
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(true, "REV", 1_200L));
    }

    @Test
    public void feedbackOpensOnlyAfterSuccessfulGenerationSettles() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        long generation = p.beginRestore();
        p.completeRestore(generation, 10_000L);

        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "COMFORT", 10_001L));
        assertEquals(ModeSyncPolicy.Decision.CORRECT,
                p.evaluate(false, "ECO", 15_000L));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT,
                p.evaluate(false, "ECO", 10_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS));
    }

    @Test
    public void staleCycleCannotOpenNewerWakeGeneration() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("SPORT", "EV", true, true);
        long oldGeneration = p.beginRestore();
        p.beginRestore();

        p.completeRestore(oldGeneration, 1_000L);

        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 5_000L));
    }

    @Test
    public void disabledModeIsIgnoredDuringWakeButCanSyncWhenStable() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("SPORT", "EV", false, false);
        long generation = p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(false, "ECO", 100L));
        p.completeRestore(generation, 1_000L);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT,
                p.evaluate(false, "ECO", 1_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS));
    }

    @Test
    public void explicitSavedChangeBecomesNewCorrectionTarget() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.updateExpectedMode(false, "SPORT");
        p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(false, "SPORT", 100L));
        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "COMFORT", 200L));
    }

    @Test
    public void sleepFreezeNeverSendsCorrection() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.freeze();

        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(false, "ECO", 100L));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(true, "REV", 5_000L));
    }

    @Test
    public void feedbackCorrectionIsLimitedAcrossRestoreGenerationsOfOneWake() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 1_000L));

        // scheduleApply(), вызванный correction, создаёт новую restore generation. Она не должна
        // обнулить wake-бюджет и превратить постоянный feedback ECO в бесконечную цепочку циклов.
        p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "ECO", 1_000L + ModeSyncPolicy.CORRECTION_COOLDOWN_MS));
        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(true, "REV", 10_000L));
    }

    @Test
    public void newWakeAfterFreezeGetsFreshCorrectionBudget() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 1_000L));

        p.freeze();
        p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 1_100L));
    }

    @Test
    public void correctionBudgetDoesNotBlockExternalPersistAfterSettle() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", true, true);
        p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 1_000L));

        long correctionGeneration = p.beginRestore();
        p.completeRestore(correctionGeneration, 5_000L);

        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate(false, "ECO",
                5_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS));
    }

    @Test
    public void failedRestoreSuppressesFeedbackCorrectionUntilNextWake() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        long generation = p.beginRestore();

        p.failRestore(generation);
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(false, "ECO", 10_000L));

        p.freeze();
        p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.CORRECT, p.evaluate(false, "ECO", 20_000L));
    }

    @Test
    public void explicitCommandInvalidatesStaleRestoreThenReopensNormalFeedback() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        long staleRestore = p.beginRestore();

        long userCommand = p.cancelRestore();

        assertFalse(p.completeRestore(staleRestore, 6_000L));
        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "ECO", Long.MAX_VALUE));
        assertTrue(p.completeUserCommand(userCommand, 5_000L));
        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "ECO", 5_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS - 1L));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT,
                p.evaluate(false, "ECO", 5_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS));
    }

    @Test
    public void explicitCancellationDoesNotRefreshSameWakeCorrectionBudget() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.CORRECT,
                p.evaluate(false, "ECO", 1_000L));

        long userCommand = p.cancelRestore();
        assertTrue(p.completeUserCommand(userCommand, 2_000L));
        p.beginRestore();

        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "ECO", 10_000L));
    }

    @Test
    public void explicitCommandAfterSleepDoesNotReopenFrozenFeedback() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        p.beginRestore();
        p.freeze();

        long userCommand = p.cancelRestore();

        assertFalse(p.completeUserCommand(userCommand, 5_000L));
        assertEquals(ModeSyncPolicy.Decision.IGNORE,
                p.evaluate(false, "ECO", Long.MAX_VALUE));
    }

    @Test
    public void lateWakeRestoreSupersedesQueuedUserCommandTerminal() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        p.beginRestore();
        long userCommand = p.cancelRestore();

        long lateWakeRestore = p.beginRestore();

        assertFalse(p.completeUserCommand(userCommand, 5_000L));
        assertTrue(p.completeRestore(lateWakeRestore, 6_000L));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT,
                p.evaluate(false, "ECO",
                        6_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS));
    }

    @Test
    public void persistenceTokenIsRejectedWhenSleepOrRestoreSupersedesIt() {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "AUTO", true, true);
        long restore = p.beginRestore();
        p.completeRestore(restore, 1_000L);
        long persistToken = p.currentGeneration();
        long stableAt = 1_000L + ModeSyncPolicy.POST_RESTORE_SETTLE_MS;
        assertTrue(p.canPersist(persistToken, stableAt));

        p.freeze();

        assertFalse(p.canPersist(persistToken, stableAt));
    }
}
