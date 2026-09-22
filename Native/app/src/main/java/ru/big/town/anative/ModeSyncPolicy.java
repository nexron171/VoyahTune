package ru.big.town.anative;

import java.util.HashMap;
import java.util.Map;

/** Saved selection and current vehicle state are independent; feedback never requests a restore. */
final class ModeSyncPolicy {
    enum Decision { ACCEPT, IGNORE }

    private long generation;
    private boolean wakeActive;
    private boolean feedbackOpen;
    private boolean driveEntered;
    private boolean waitingForDrive;
    private int lastGear = -1;
    private String expectedDrive;
    private final Map<String, String> currentModes = new HashMap<>();
    private boolean driveRememberLast = true;
    private boolean energyRememberLast = true;
    private boolean recycleRememberLast = true;

    synchronized void activateWake() { wakeActive = true; }

    synchronized void onDriverDoorOpened() {
        driveEntered = false;
        waitingForDrive = true;
        feedbackOpen = false;
        // A completion or persistence check from the previous door cycle is no longer valid.
        generation++;
    }

    synchronized void onGear(int gear) {
        if (gear < 0) return;
        if (wakeActive && waitingForDrive && gear == 3 && lastGear != 3) {
            driveEntered = true;
            waitingForDrive = false;
        }
        lastGear = gear;
    }

    /** Also guards steering selections, which are saved while their command is still running. */
    synchronized boolean canRememberSelection() { return wakeActive && driveEntered; }

    synchronized long beginRestore() {
        wakeActive = true;
        feedbackOpen = false;
        return ++generation;
    }

    synchronized long freeze() {
        wakeActive = false;
        feedbackOpen = false;
        driveEntered = false;
        waitingForDrive = false;
        lastGear = -1;
        currentModes.clear();
        return ++generation;
    }

    synchronized long cancelRestore() {
        feedbackOpen = false;
        return ++generation;
    }

    synchronized boolean completeUserCommand(long candidate) {
        return completeRestore(candidate);
    }

    synchronized boolean completeRestore(long candidate) {
        if (candidate != generation || !wakeActive) return false;
        feedbackOpen = true;
        return true;
    }

    synchronized boolean failRestore(long candidate) {
        if (candidate != generation) return false;
        feedbackOpen = false;
        return true;
    }

    synchronized long currentGeneration() { return generation; }

    synchronized boolean canPersist(long candidate, String modeKey) {
        return candidate == generation && canRememberSelection()
                && feedbackOpen && acceptsExternalFeedback(modeKey);
    }

    synchronized void updateExpected(String drive, String energy, String recycle,
                                     boolean driveEnabled, boolean energyEnabled,
                                     boolean recycleEnabled, boolean driveRememberLast,
                                     boolean energyRememberLast, boolean recycleRememberLast) {
        if (valid(drive)) expectedDrive = drive;
        this.driveRememberLast = driveRememberLast;
        this.energyRememberLast = energyRememberLast;
        this.recycleRememberLast = recycleRememberLast;
    }

    synchronized void updateExpectedMode(String modeKey, String mode) {
        if ("driveMode".equals(modeKey) && valid(mode)) expectedDrive = mode;
    }

    synchronized void updateRememberLast(String modeKey, boolean rememberLast) {
        if ("driveMode".equals(modeKey)) driveRememberLast = rememberLast;
        else if ("energy".equals(modeKey)) energyRememberLast = rememberLast;
        else if ("recycle".equals(modeKey)) recycleRememberLast = rememberLast;
    }

    synchronized void observe(String modeKey, String mode) {
        if (knownModeKey(modeKey) && valid(mode)) currentModes.put(modeKey, mode);
    }

    synchronized String currentMode(String modeKey, String fallback) {
        return currentModes.getOrDefault(modeKey, fallback);
    }

    synchronized Decision evaluate(String modeKey, String observedMode) {
        if (!knownModeKey(modeKey) || !valid(observedMode)) return Decision.IGNORE;
        // Even opted-out feedback is needed for steering cycles and Snow recuperation handling.
        observe(modeKey, observedMode);
        return canRememberSelection() && feedbackOpen && acceptsExternalFeedback(modeKey)
                ? Decision.ACCEPT : Decision.IGNORE;
    }

    private boolean acceptsExternalFeedback(String modeKey) {
        if ("energy".equals(modeKey)) return energyRememberLast;
        if ("driveMode".equals(modeKey)) return driveRememberLast;
        return "recycle".equals(modeKey) && recycleRememberLast
                && !"SNOW".equals(currentMode("driveMode", expectedDrive));
    }

    private static boolean knownModeKey(String key) {
        return "driveMode".equals(key) || "energy".equals(key) || "recycle".equals(key);
    }

    private static boolean valid(String mode) { return mode != null && !mode.isEmpty(); }
}
