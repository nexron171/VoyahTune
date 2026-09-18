package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeSyncPolicyTest {
    private ModeSyncPolicy policy(boolean remember) {
        ModeSyncPolicy p = new ModeSyncPolicy();
        p.updateExpected("COMFORT", "SREV", "HIGH", true, true, true,
                remember, remember, remember);
        return p;
    }

    @Test public void feedbackIsIgnoredDuringRestoreAndOpensImmediatelyOnCompletion() {
        ModeSyncPolicy p = policy(true);
        long generation = p.beginRestore();
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        assertTrue(p.completeRestore(generation));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
        assertTrue(p.canPersist(generation, "driveMode"));
    }

    @Test public void optedOutModesTrackVehicleWithoutChangingSavedSelection() {
        ModeSyncPolicy p = policy(false);
        long generation = p.beginRestore();
        p.completeRestore(generation);
        String[] keys = {"driveMode", "energy", "recycle"};
        String[] modes = {"SPORT", "EV", "LOW"};
        for (int i = 0; i < keys.length; i++) {
            assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate(keys[i], modes[i]));
            assertFalse(p.canPersist(generation, keys[i]));
            assertEquals(modes[i], p.currentMode(keys[i], "saved"));
        }
    }

    @Test public void consecutiveSteeringClicksCycleWhileRememberLastIsOff() {
        ModeSyncPolicy p = policy(false);
        p.observe("driveMode", "COMFORT");
        String next = SteeringActionPolicy.nextMode("COMFORT,SPORT,ECO",
                p.currentMode("driveMode", "COMFORT"));
        assertEquals("SPORT", next);
        p.observe("driveMode", next);
        next = SteeringActionPolicy.nextMode("COMFORT,SPORT,ECO",
                p.currentMode("driveMode", "COMFORT"));
        assertEquals("ECO", next);
        p.observe("driveMode", next);
        // Loading the saved menu selection must not move the steering cursor back to COMFORT.
        p.updateExpected("COMFORT", "SREV", "HIGH", true, true, true, false, false, false);
        assertEquals("ECO", p.currentMode("driveMode", "COMFORT"));
    }

    @Test public void externalSelectionUpdatesSteeringCursorEvenWhenRememberLastIsOff() {
        ModeSyncPolicy p = policy(false);
        p.observe("driveMode", "SPORT");
        p.evaluate("driveMode", "ECO");
        assertEquals("COMFORT", SteeringActionPolicy.nextMode("COMFORT,SPORT,ECO",
                p.currentMode("driveMode", "COMFORT")));
    }

    @Test public void preferenceChangeImmediatelyRevalidatesPendingFeedback() {
        ModeSyncPolicy p = policy(true);
        long generation = p.beginRestore();
        p.completeRestore(generation);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "EV"));
        p.updateRememberLast("energy", false);
        assertFalse(p.canPersist(generation, "energy"));
        assertTrue(p.canPersist(generation, "driveMode"));
        p.updateRememberLast("energy", true);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
    }

    @Test public void secondRestoreStillRunsAndOnlyItsCompletionOpensFeedback() {
        ModeSyncPolicy p = policy(true);
        long door = p.beginRestore();
        long drive = p.beginRestore();
        assertFalse(p.completeRestore(door));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        assertTrue(p.completeRestore(drive));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
    }

    @Test public void sleepAndUserCommandsInvalidateOldCompletions() {
        ModeSyncPolicy p = policy(true);
        long restore = p.beginRestore();
        long command = p.cancelRestore();
        assertFalse(p.completeRestore(restore));
        assertTrue(p.completeUserCommand(command));
        p.freeze();
        assertFalse(p.completeUserCommand(command));
        assertEquals("COMFORT", p.currentMode("driveMode", "COMFORT"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
    }

    @Test public void failedRestoreWaitsForAnotherEventWithoutAcceptingDefaults() {
        ModeSyncPolicy p = policy(true);
        long door = p.beginRestore();
        assertTrue(p.failRestore(door));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        long drive = p.beginRestore();
        assertFalse(p.failRestore(door));
        assertTrue(p.completeRestore(drive));
    }

    @Test public void snowGuardUsesActualVehicleModeInsteadOfPinnedMenuSelection() {
        ModeSyncPolicy p = policy(true);
        p.completeRestore(p.beginRestore());
        p.observe("driveMode", "SNOW");
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "LOW"));
        p.updateExpectedMode("driveMode", "SNOW");
        p.observe("driveMode", "COMFORT");
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("recycle", "HIGH"));
    }

    @Test public void invalidFeedbackDoesNotChangeVehicleState() {
        ModeSyncPolicy p = policy(true);
        p.completeRestore(p.beginRestore());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("unknown", "ECO"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", ""));
        assertEquals("COMFORT", p.currentMode("driveMode", "COMFORT"));
    }
}
