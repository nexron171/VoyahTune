package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
