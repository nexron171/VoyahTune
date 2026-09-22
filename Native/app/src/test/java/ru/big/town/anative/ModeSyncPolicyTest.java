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

    @Test public void completedDoorRestoreDoesNotRememberWakeDefaultsBeforeDrive() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        long door = p.beginRestore();
        assertTrue(p.completeRestore(door));
        for (int gear : new int[]{-1, 0, 2, 1}) {
            p.onGear(gear);
            assertFalse(p.canRememberSelection());
            assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
            assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
            assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "LOW"));
            assertFalse(p.canPersist(door, "driveMode"));
            assertFalse(p.canPersist(door, "energy"));
            assertFalse(p.canPersist(door, "recycle"));
        }
        // Live state is still available to steering without overwriting the saved selection.
        assertEquals("ECO", p.currentMode("driveMode", "COMFORT"));
        long drive = p.beginRestore();
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        assertTrue(p.completeRestore(drive));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("recycle", "HIGH"));
    }

    @Test public void driveWithoutDoorCannotEnableRememberingEvenAfterManualApply() {
        ModeSyncPolicy p = policy(true);
        p.completeRestore(p.beginRestore());
        p.onGear(3);
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
    }

    @Test public void userCommandBeforeDriveCannotBypassRememberingGate() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        long command = p.cancelRestore();
        assertFalse(p.canRememberSelection()); // Steering persists inside the command.
        assertTrue(p.completeUserCommand(command));
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "SPORT"));
        p.onGear(3);
        command = p.cancelRestore();
        assertTrue(p.canRememberSelection());
        assertTrue(p.completeUserCommand(command));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
    }

    @Test public void nextDoorBlocksAgainAndDuplicateDriveIsNotANewEntry() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        long previous = p.beginRestore();
        p.onGear(3);
        p.completeRestore(previous);
        assertTrue(p.canPersist(previous, "energy"));
        p.onDriverDoorOpened();
        assertFalse(p.canPersist(previous, "energy"));
        assertFalse(p.completeRestore(previous));
        p.completeRestore(p.beginRestore());
        p.onGear(-1);
        p.onGear(3);
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        p.onGear(2);
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
    }

    @Test public void sleepClosesRememberingUntilNextDoorAndDrive() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        long first = p.beginRestore();
        p.onGear(3);
        p.completeRestore(first);
        p.freeze();
        assertFalse(p.canPersist(first, "driveMode"));
        p.activateWake();
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
    }

    @Test public void parkingAndConnectionWakeNotificationsKeepRememberingEnabled() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        p.onGear(3);
        for (int gear : new int[]{2, 3, 0, 3, 1, 3}) {
            p.activateWake();
            p.onGear(gear);
            assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
        }
    }

    @Test public void enablingRememberLastBeforeDriveDoesNotAcceptWakeDefaults() {
        ModeSyncPolicy p = policy(false);
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        p.updateRememberLast("energy", true);
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "SPORT"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "LOW"));
    }

    @Test public void feedbackIsIgnoredDuringRestoreAndOpensImmediatelyOnCompletion() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        long generation = p.beginRestore();
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        assertTrue(p.completeRestore(generation));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("driveMode", "SPORT"));
        assertTrue(p.canPersist(generation, "driveMode"));
    }

    @Test public void optedOutModesTrackVehicleWithoutChangingSavedSelection() {
        ModeSyncPolicy p = policy(false);
        p.onDriverDoorOpened();
        long generation = p.beginRestore();
        p.onGear(3);
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
        p.onDriverDoorOpened();
        long generation = p.beginRestore();
        p.onGear(3);
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
        p.onDriverDoorOpened();
        long door = p.beginRestore();
        long drive = p.beginRestore();
        p.onGear(3);
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
        p.onDriverDoorOpened();
        long door = p.beginRestore();
        assertTrue(p.failRestore(door));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
        long drive = p.beginRestore();
        p.onGear(3);
        assertFalse(p.failRestore(door));
        assertTrue(p.completeRestore(drive));
    }

    @Test public void snowGuardUsesActualVehicleModeInsteadOfPinnedMenuSelection() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        p.onGear(3);
        p.observe("driveMode", "SNOW");
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "LOW"));
        p.updateExpectedMode("driveMode", "SNOW");
        p.observe("driveMode", "COMFORT");
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("recycle", "HIGH"));
    }

    @Test public void invalidFeedbackDoesNotChangeVehicleState() {
        ModeSyncPolicy p = policy(true);
        p.onDriverDoorOpened();
        p.completeRestore(p.beginRestore());
        p.onGear(3);
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("unknown", "ECO"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", ""));
        assertEquals("COMFORT", p.currentMode("driveMode", "COMFORT"));
    }
}
