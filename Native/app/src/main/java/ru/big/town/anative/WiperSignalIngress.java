package ru.big.town.anative;

import java.util.ArrayDeque;

/** Bounded, Android-free ingress filter for the two Wiper CanBus signals. */
final class WiperSignalIngress {
    static final int UNKNOWN = -1;

    private final int doorCapacity;
    private final ArrayDeque<Integer> doorTransitions = new ArrayDeque<>();

    private int lastAcceptedDoor = UNKNOWN;
    private boolean doorDrainScheduled;
    private int latestGear = UNKNOWN;
    private boolean gearDrainScheduled;
    private long droppedDoorTransitions;

    WiperSignalIngress(int doorCapacity) {
        if (doorCapacity < 2) throw new IllegalArgumentException("doorCapacity must be >= 2");
        this.doorCapacity = doorCapacity;
    }

    /** @return true when the caller must schedule the single door drain. */
    synchronized boolean offerDoor(int value) {
        if ((value != 0 && value != 1) || value == lastAcceptedDoor) return false;
        lastAcceptedDoor = value;
        if (doorTransitions.size() == doorCapacity) {
            doorTransitions.removeFirst();
            droppedDoorTransitions++;
        }
        doorTransitions.addLast(value);
        if (doorDrainScheduled) return false;
        doorDrainScheduled = true;
        return true;
    }

    synchronized Integer pollDoor() {
        return doorTransitions.pollFirst();
    }

    /** Aligns dedupe with a synchronous level snapshot when no real edge is already pending. */
    synchronized void seedDoor(int value) {
        if ((value == 0 || value == 1) && doorTransitions.isEmpty()) {
            lastAcceptedDoor = value;
        }
    }

    /** Called by the drain after a bounded slice. */
    synchronized boolean finishDoorDrainSlice() {
        if (!doorTransitions.isEmpty()) return true;
        doorDrainScheduled = false;
        return false;
    }

    synchronized void cancelDoorDrain() {
        doorDrainScheduled = false;
    }

    /** @return true when the caller must schedule the single latest-value gear drain. */
    synchronized boolean offerGear(int value) {
        if (value < 0 || value == latestGear) return false;
        latestGear = value;
        if (gearDrainScheduled) return false;
        gearDrainScheduled = true;
        return true;
    }

    synchronized int takeLatestGear() {
        if (!gearDrainScheduled) return UNKNOWN;
        gearDrainScheduled = false;
        return latestGear;
    }

    synchronized void cancelGearDrain() {
        gearDrainScheduled = false;
    }

    synchronized long droppedDoorTransitions() {
        return droppedDoorTransitions;
    }

    synchronized int queuedDoorCount() {
        return doorTransitions.size();
    }

    synchronized void clear() {
        doorTransitions.clear();
        doorDrainScheduled = false;
        gearDrainScheduled = false;
    }
}
