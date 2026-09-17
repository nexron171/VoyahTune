package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeRestoreTriggersTest {
    @Test public void doorAndDriveEachRestoreRegardlessOfTheirOrder() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertTrue(p.onDoor(1));
        assertTrue(p.onGear(3));
        p.reset();
        assertTrue(p.onGear(3));
        assertTrue(p.onDoor(1));
    }

    @Test public void onlyOpeningAndEnteringDriveRestore() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertFalse(p.onDoor(-1));
        assertFalse(p.onDoor(0));
        assertFalse(p.onGear(0));
        assertFalse(p.onGear(1));
        assertFalse(p.onGear(2));
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(1));
        assertTrue(p.onGear(3));
        assertFalse(p.onGear(3));
        assertFalse(p.onDoor(0));
        assertTrue(p.onDoor(1));
        assertFalse(p.onGear(2));
        assertTrue(p.onGear(3));
    }

    @Test public void reconnectCanRecoverAnAlreadyOpenDoorOrDrive() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertTrue(p.onDoor(1));
        assertTrue(p.onGear(3));
        p.reset();
        assertTrue(p.onDoor(1));
        assertTrue(p.onGear(3));
    }
}
