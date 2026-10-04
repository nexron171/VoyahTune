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

    @Test public void accRestoreOpensFeedbackAfterCompletion() {
        ModeSyncPolicy p = policy(true);
        long restore = p.beginRestore();
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        assertTrue(p.completeRestore(restore));
        assertTrue(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
        assertTrue(p.canPersist(restore, "recycle"));
    }

    @Test public void cancelledRestoreCannotOpenFeedback() {
        ModeSyncPolicy p = policy(true);
        long restore = p.beginRestore();
        long command = p.cancelRestore();
        assertFalse(p.completeRestore(restore));
        assertFalse(p.canRememberSelection());
        assertTrue(p.completeUserCommand(command));
        assertTrue(p.canRememberSelection());
    }

    @Test public void sleepClosesFeedbackUntilNewRestore() {
        ModeSyncPolicy p = policy(true);
        long old = p.beginRestore();
        p.completeRestore(old);
        p.freeze();
        assertFalse(p.canPersist(old, "energy"));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        long next = p.beginRestore();
        assertTrue(p.completeRestore(next));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
    }

    @Test public void rememberOptOutAndSnowRecuperationRemainProtected() {
        ModeSyncPolicy p = policy(false);
        p.completeRestore(p.beginRestore());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        p.updateRememberLast("energy", true);
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
        p.updateRememberLast("recycle", true);
        p.observe("driveMode", "SNOW");
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "HIGH"));
    }

    @Test public void explicitRecuperationDoesNotWaitForCommandCompletionOrVehicleEcho() {
        ModeSyncPolicy p = policy(true);
        p.beginRestore();
        p.cancelRestore(); // postUserCommand closes feedback before running the command.
        assertFalse(p.canRememberSelection(false));
        assertTrue(p.canRememberSelection(true));
        p.observe("driveMode", "SNOW");
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("recycle", "LOW"));
        assertTrue(p.canRememberSelection(true));
        p.freeze(); // Persist an executed command even if sleep races its completion.
        assertFalse(p.canRememberSelection(false));
        assertTrue(p.canRememberSelection(true));
    }

    @Test public void nativeRestartReusesSubmittedAccWithoutWaitingForAnotherRestore() {
        ModeSyncPolicy p = policy(true);
        p.activateWake();
        assertFalse(p.canRememberSelection());
        assertTrue(p.reconcileCompletedAcc(2, "submitted"));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("recycle", "MEDIUM"));
    }

    @Test public void pendingUncertainOffAndFrozenCyclesCannotReopenFeedback() {
        for (String state : new String[]{"idle", "pending", "claimed", "uncertain"}) {
            ModeSyncPolicy p = policy(true);
            p.activateWake();
            assertFalse(p.reconcileCompletedAcc(2, state));
            assertFalse(p.canRememberSelection());
        }
        ModeSyncPolicy p = policy(true);
        p.activateWake();
        assertFalse(p.reconcileCompletedAcc(0, "submitted"));
        p.freeze();
        p.activateWake();
        assertFalse(p.reconcileCompletedAcc(2, "submitted"));
        assertFalse(p.canRememberSelection());
    }

    @Test public void duplicateServiceStartCannotOpenFeedbackDuringAnExplicitCommand() {
        ModeSyncPolicy p = policy(true);
        p.activateWake();
        p.reconcileCompletedAcc(2, "submitted");
        long command = p.cancelRestore();
        assertFalse(p.reconcileCompletedAcc(2, "submitted"));
        assertFalse(p.canRememberSelection());
        assertTrue(p.completeUserCommand(command));
    }

    @Test public void nativeRestoreNeedsBothFirstDriveAndCompletedPassForFeedback() {
        ModeSyncPolicy p = policy(true);
        p.useAccHooks(false);
        p.activateWake();
        assertFalse(p.reconcileCompletedAcc(2, "submitted"));
        p.onDriverDoorOpened();
        long door = p.beginRestore();
        assertTrue(p.completeRestore(door));
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("energy", "EV"));
        p.onGear(0);
        long drive = p.beginRestore();
        p.onGear(3);
        assertTrue(p.canRememberSelection());
        assertFalse(p.canPersist(drive, "energy"));
        assertTrue(p.completeRestore(drive));
        assertEquals(ModeSyncPolicy.Decision.ACCEPT, p.evaluate("energy", "REV"));
        assertTrue(p.canPersist(drive, "energy"));
        p.onDriverDoorOpened();
        assertFalse(p.canPersist(drive, "energy"));
        assertFalse(p.completeRestore(drive));
    }

    @Test public void accRestoreIgnoresDoorAndGearGates() {
        ModeSyncPolicy p = policy(true);
        long restore = p.beginRestore();
        p.onDriverDoorOpened();
        p.onGear(0);
        assertTrue(p.completeRestore(restore));
        assertTrue(p.canRememberSelection());
        p.onDriverDoorOpened();
        assertTrue(p.canPersist(restore, "recycle"));
    }

    @Test public void nativeRestoreFeedbackIsFrozenAgainAfterSleep() {
        ModeSyncPolicy p = policy(true);
        p.useAccHooks(false);
        p.activateWake();
        p.onDriverDoorOpened();
        p.onGear(0);
        p.onGear(3);
        long first = p.beginRestore();
        p.completeRestore(first);
        p.freeze();
        p.activateWake();
        p.completeRestore(p.beginRestore());
        assertFalse(p.canRememberSelection());
        assertEquals(ModeSyncPolicy.Decision.IGNORE, p.evaluate("driveMode", "ECO"));
    }
}
