package ru.big.town.anative;

/**
 * Android-free state machine separating an OEM wake reset from a real external mode selection.
 *
 * <p>A successful CAN write only means that the command reached the bus. The car may still publish
 * and apply its default mode later in the wake sequence. Therefore feedback remains read-only for a
 * settling interval after restore. A conflicting value in that interval requests another restore;
 * it is never allowed to replace the saved source of truth.</p>
 */
final class ModeSyncPolicy {
    static final long POST_RESTORE_SETTLE_MS = 20_000L;
    static final long CORRECTION_COOLDOWN_MS = 3_000L;

    enum Decision {
        /** Stable awake state: feedback may be persisted as an external user selection. */
        ACCEPT,
        /** Expected restore echo, disabled mode, invalid input, or correction already in flight. */
        IGNORE,
        /** Wake feedback conflicts with the saved mode: re-run restore, do not persist feedback. */
        CORRECT
    }

    private long generation;
    private boolean restoreCompleted;
    private boolean correctionAllowed;
    private long acceptAfterUptime = Long.MAX_VALUE;
    private long lastCorrectionUptime = Long.MIN_VALUE;

    private String expectedDrive;
    private String expectedEnergy;
    private boolean driveEnabled;
    private boolean energyEnabled;

    /** Starts a new guarded restore generation while retaining the last known saved snapshot. */
    synchronized long beginRestore() {
        generation++;
        restoreCompleted = false;
        correctionAllowed = true;
        acceptAfterUptime = Long.MAX_VALUE;
        return generation;
    }

    /** Freezes feedback for sleep/shutdown without sending corrective CAN while the car powers down. */
    synchronized long freeze() {
        generation++;
        restoreCompleted = false;
        correctionAllowed = false;
        acceptAfterUptime = Long.MAX_VALUE;
        return generation;
    }

    /** Refreshes the source-of-truth snapshot loaded from RestoreMode/provider or Native cache. */
    synchronized void updateExpected(String drive, String energy,
                                     boolean driveEnabled, boolean energyEnabled) {
        if (valid(drive)) expectedDrive = drive;
        if (valid(energy)) expectedEnergy = energy;
        this.driveEnabled = driveEnabled;
        this.energyEnabled = energyEnabled;
    }

    /** Updates one explicitly saved mode immediately (steering button or accepted external change). */
    synchronized void updateExpectedMode(boolean energy, String mode) {
        if (!valid(mode)) return;
        if (energy) expectedEnergy = mode;
        else expectedDrive = mode;
    }

    /** Opens feedback only after the matching generation has restored and then settled. */
    synchronized boolean completeRestore(long completedGeneration, long nowUptime) {
        if (completedGeneration != generation) return false;
        restoreCompleted = true;
        acceptAfterUptime = saturatedAdd(nowUptime, POST_RESTORE_SETTLE_MS);
        return true;
    }

    synchronized Decision evaluate(boolean energy, String observedMode, long nowUptime) {
        if (!valid(observedMode)) return Decision.IGNORE;
        if (restoreCompleted && nowUptime >= acceptAfterUptime) return Decision.ACCEPT;

        String expected = energy ? expectedEnergy : expectedDrive;
        boolean enabled = energy ? energyEnabled : driveEnabled;
        if (!correctionAllowed || !enabled || !valid(expected) || expected.equals(observedMode)) {
            return Decision.IGNORE;
        }

        if (lastCorrectionUptime == Long.MIN_VALUE
                || nowUptime - lastCorrectionUptime >= CORRECTION_COOLDOWN_MS) {
            lastCorrectionUptime = nowUptime;
            return Decision.CORRECT;
        }
        return Decision.IGNORE;
    }

    private static boolean valid(String mode) {
        return mode != null && !mode.isEmpty();
    }

    private static long saturatedAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
