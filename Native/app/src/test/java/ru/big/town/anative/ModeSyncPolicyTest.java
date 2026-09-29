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
}
