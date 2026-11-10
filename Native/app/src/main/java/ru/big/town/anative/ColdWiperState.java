package ru.big.town.anative;

/** Desired service position; wake and unknown telemetry never release the parking latch. */
final class ColdWiperState {
    private static final int DOOR_OPEN = 1;
    private static final int DOOR_CLOSED = 0;
    private static final int PARK = 0;
    private static final int DRIVE = 3;

    private boolean active;
    private boolean desiredActive;
    private boolean enabled;
    private boolean releaseForDrive;
    private boolean waitForDoorClose;
    private boolean positionUncertain;
    private Boolean pendingTarget;
    private int door = -1;
    private int gear = -1;

    ColdWiperState(boolean active, boolean waitForDoorClose) {
        this(active, waitForDoorClose, false);
    }

    ColdWiperState(boolean active, boolean waitForDoorClose, boolean positionUncertain) {
        this.active = active;
        desiredActive = active;
        this.waitForDoorClose = waitForDoorClose;
        this.positionUncertain = positionUncertain;
    }

    synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        evaluateDoor();
    }

    synchronized void onDoor(int door) {
        if (door != DOOR_CLOSED && door != DOOR_OPEN) return;
        this.door = door;
        if (door == DOOR_CLOSED) waitForDoorClose = false;
        evaluateDoor();
    }

    synchronized void onGear(int gear) {
        onGear(gear, true);
    }

    synchronized void onGear(int gear, boolean confirmed) {
        if (gear < PARK || gear > 4) return;
        this.gear = gear;
        if (gear == DRIVE && confirmed) {
            releaseForDrive = true;
            desiredActive = false;
            waitForDoorClose = door != DOOR_CLOSED;
        } else if (gear != DRIVE && !active && !Boolean.TRUE.equals(pendingTarget)) {
            releaseForDrive = false;
        }
        evaluateDoor();
    }

    synchronized void reconcile(boolean persistedActive, boolean persistedUncertain) {
        positionUncertain = persistedUncertain;
        if (active != persistedActive) updateAcceptedPosition(persistedActive);
        evaluateDoor();
    }

    synchronized Boolean nextTarget() {
        return positionUncertain || desiredActive == active ? null : desiredActive;
    }

    synchronized boolean canSend(boolean target) {
        return !positionUncertain && desiredActive == target && active != target;
    }

    synchronized void onRequested(boolean target) {
        pendingTarget = target;
    }

    synchronized void onFrameAttempted() {
        positionUncertain = true;
    }

    synchronized boolean positionUncertain() {
        return positionUncertain;
    }

    synchronized void onFailed() {
        pendingTarget = null;
        if (!active && gear != DRIVE) releaseForDrive = false;
        evaluateDoor();
    }

    synchronized void onSent(boolean target) {
        updateAcceptedPosition(target);
        positionUncertain = false;
    }

    private void updateAcceptedPosition(boolean target) {
        pendingTarget = null;
        active = target;
        desiredActive = target && !releaseForDrive;
        if (!target) releaseForDrive = false;
    }

    synchronized boolean waitForDoorClose() {
        return waitForDoorClose;
    }

    private void evaluateDoor() {
        if (active) return;
        desiredActive = enabled
                && door == DOOR_OPEN
                && !waitForDoorClose
                && !releaseForDrive
                && (gear < 0 || gear == PARK);
    }
}
