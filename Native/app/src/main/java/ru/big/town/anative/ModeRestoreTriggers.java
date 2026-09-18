package ru.big.town.anative;

/** Detects the two independent restore events; there is no shared wake/deduplication budget. */
final class ModeRestoreTriggers {
    private static final int DOOR_OPEN = 1;
    private static final int GEAR_DRIVE = 3;
    private int lastDoor = -1;
    private int lastGear = -1;

    boolean onDoor(int door) {
        if (door < 0) return false;
        boolean opened = door == DOOR_OPEN && lastDoor != DOOR_OPEN;
        lastDoor = door;
        return opened;
    }

    boolean onGear(int gear) {
        if (gear < 0) return false;
        boolean enteredDrive = gear == GEAR_DRIVE && lastGear != GEAR_DRIVE;
        lastGear = gear;
        return enteredDrive;
    }

    void reset() {
        lastDoor = -1;
        lastGear = -1;
    }
}
