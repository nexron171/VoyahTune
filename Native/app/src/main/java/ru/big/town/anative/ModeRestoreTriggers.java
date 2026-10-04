package ru.big.town.anative;

/** Restores on door opening and once on the next entry into Drive after that opening. */
final class ModeRestoreTriggers {
    private static final int DOOR_OPEN = 1;
    private static final int GEAR_DRIVE = 3;
    private int lastDoor = -1;
    private int lastGear = -1;
    private boolean driveRestorePending;

    boolean onDoor(int door) {
        if (door < 0) return false;
        boolean opened = door == DOOR_OPEN && lastDoor != DOOR_OPEN;
        lastDoor = door;
        if (opened) driveRestorePending = true;
        return opened;
    }

    boolean onGear(int gear) {
        if (gear < 0) return false;
        boolean enteredDrive = gear == GEAR_DRIVE && lastGear != GEAR_DRIVE;
        lastGear = gear;
        if (!enteredDrive || !driveRestorePending) return false;
        // Consume before scheduling: duplicate callbacks and later parking manoeuvres must not
        // restore again, even if this attempt fails or is cancelled. Only a new opening rearms it.
        driveRestorePending = false;
        return true;
    }
}
