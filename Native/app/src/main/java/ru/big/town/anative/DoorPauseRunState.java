package ru.big.town.anative;

/** Android-free single-flight state for one door pause/fade run. */
final class DoorPauseRunState {
    static final int REJECTED_GENERATION = -1;
    static final int NO_RESTORE_VOLUME = Integer.MIN_VALUE;

    private int generation;
    private boolean busy;
    private int restoreVolume = NO_RESTORE_VOLUME;

    synchronized boolean isBusy() {
        return busy;
    }

    synchronized int begin(int capturedVolume) {
        if (busy) return REJECTED_GENERATION;
        busy = true;
        restoreVolume = capturedVolume;
        return ++generation;
    }

    synchronized boolean isCurrent(int candidateGeneration) {
        return busy && candidateGeneration == generation;
    }

    synchronized int finishAndTakeRestoreVolume(int candidateGeneration) {
        if (!isCurrent(candidateGeneration)) return NO_RESTORE_VOLUME;
        int captured = restoreVolume;
        busy = false;
        restoreVolume = NO_RESTORE_VOLUME;
        return captured;
    }

    synchronized int cancelAndTakeRestoreVolume() {
        int captured = busy ? restoreVolume : NO_RESTORE_VOLUME;
        generation++;
        busy = false;
        restoreVolume = NO_RESTORE_VOLUME;
        return captured;
    }
}
