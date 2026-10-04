package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeRestoreTriggersTest {
    @Test public void driveRequiresAnObservedDoorOpening() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertFalse(p.onGear(3));
        assertFalse(p.onDoor(0));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
        assertTrue(p.onDoor(1));
        assertFalse(p.onGear(3)); // Already in Drive: not a new entry.
        assertFalse(p.onGear(2));
        assertTrue(p.onGear(3));
    }

    @Test public void parkingAfterManualModeChangeDoesNotRestoreAgain() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(0));
        assertTrue(p.onGear(3));
        // The driver now selects Sport. Every later R/N/P -> D must leave it alone.
        for (int gear : new int[]{2, 1, 0, 2, 0}) {
            assertFalse(p.onGear(gear));
            assertFalse(p.onGear(3));
        }
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(0));
        assertFalse(p.onGear(2));
        assertTrue(p.onGear(3));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
    }

    @Test public void repeatedOpenDoorReportsDoNotRearmDrive() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(1));
        assertTrue(p.onGear(3));
        assertFalse(p.onGear(3));
        // Also covers the same open-door snapshot delivered after a CAN reconnect.
        assertFalse(p.onDoor(1));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
        assertFalse(p.onDoor(0));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
    }

    @Test public void doorMayCloseAndReverseMayPrecedeFirstDrive() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertFalse(p.onGear(0));
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(0));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(1));
        assertTrue(p.onGear(3));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
    }

    @Test public void multipleOpeningsBeforeDriveDoNotAccumulateRestores() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(0));
        assertTrue(p.onDoor(1));
        assertFalse(p.onDoor(0));
        assertTrue(p.onGear(3));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
    }

    @Test public void invalidSamplesNeitherArmNorConsumeNorCreateEdges() {
        ModeRestoreTriggers p = new ModeRestoreTriggers();
        assertFalse(p.onDoor(-1));
        assertFalse(p.onGear(-1));
        assertFalse(p.onGear(3));
        assertTrue(p.onDoor(1));
        assertFalse(p.onGear(-1));
        assertFalse(p.onGear(3));
        assertFalse(p.onGear(2));
        assertTrue(p.onGear(3));
        assertFalse(p.onDoor(-1));
        assertFalse(p.onDoor(1));
        assertFalse(p.onGear(-1));
        assertFalse(p.onGear(2));
        assertFalse(p.onGear(3));
    }
}
