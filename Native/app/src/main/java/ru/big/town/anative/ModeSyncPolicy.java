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
    static final long POST_RESTORE_SETTLE_MS = 30_000L;
    static final long CORRECTION_COOLDOWN_MS = 3_000L;
    static final int MAX_CORRECTIONS_PER_WAKE = 1;

    enum Decision {
        /** Stable awake state: feedback may be persisted as an external user/car selection. */
        ACCEPT,
        /** Expected restore echo, disabled mode, invalid input, or correction already in flight. */
        IGNORE,
        /** Wake feedback conflicts with the saved mode: re-run restore, do not persist feedback. */
        CORRECT
    }

    private long generation;
    private boolean restoreCompleted;
    private boolean correctionAllowed;
    private boolean wakeActive;
    private long acceptAfterUptime = Long.MAX_VALUE;
    private long lastCorrectionUptime = Long.MIN_VALUE;
    private int correctionsThisWake;

    private String expectedDrive;
    private String expectedEnergy;
    private boolean driveEnabled;
    private boolean energyEnabled;

    /**
     * Starts a new guarded restore generation while retaining the last known saved snapshot.
     *
     * <p>Several wake signals (and a correction requested by feedback) may create several restore
     * generations during one physical wake. The correction budget is intentionally reset only
     * after {@link #freeze()}, not on every such generation, otherwise each correction would give
     * itself a fresh budget and conflicting OEM feedback could create an endless restore storm.</p>
     */
    synchronized long beginRestore() {
        if (!wakeActive) {
            wakeActive = true;
            correctionsThisWake = 0;
            lastCorrectionUptime = Long.MIN_VALUE;
        }
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
        wakeActive = false;
        acceptAfterUptime = Long.MAX_VALUE;
        return generation;
    }

    /**
     * Invalidates an automatic restore superseded by an explicit user command.
     *
     * <p>This is deliberately not {@link #freeze()}: the physical wake and its correction budget
     * continue. Feedback stays closed while the command is queued/running; its matching terminal
     * calls {@link #completeUserCommand(long, long)} to start the normal settling delay. If sleep
     * wins the race, that stale terminal cannot reopen the frozen gate.</p>
     */
    synchronized long cancelRestore() {
        generation++;
        restoreCompleted = false;
        correctionAllowed = false;
        acceptAfterUptime = Long.MAX_VALUE;
        return generation;
    }

    /** Starts settle after the matching explicit command terminates, without creating corrections. */
    synchronized boolean completeUserCommand(long commandGeneration, long nowUptime) {
        if (commandGeneration != generation || !wakeActive) return false;
        restoreCompleted = true;
        correctionAllowed = false;
        acceptAfterUptime = saturatedAdd(nowUptime, POST_RESTORE_SETTLE_MS);
        return true;
    }

    /** Captures the feedback-gate generation for a lock-free persistence handoff. */
    synchronized long currentGeneration() {
        return generation;
    }

    /** Pure revalidation immediately before potentially blocking provider persistence. */
    synchronized boolean canPersist(long candidateGeneration, long nowUptime) {
        return candidateGeneration == generation
                && restoreCompleted
                && nowUptime >= acceptAfterUptime;
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

    /** A bounded restore window failed: keep feedback read-only and suppress correction recursion. */
    synchronized boolean failRestore(long failedGeneration) {
        if (failedGeneration != generation) return false;
        restoreCompleted = false;
        correctionAllowed = false;
        acceptAfterUptime = Long.MAX_VALUE;
        return true;
    }

    synchronized Decision evaluate(boolean energy, String observedMode, long nowUptime) {
        if (!valid(observedMode)) return Decision.IGNORE;
        // The car is a valid source of truth only after the wake-default window has elapsed.
        if (restoreCompleted && nowUptime >= acceptAfterUptime) return Decision.ACCEPT;

        String expected = energy ? expectedEnergy : expectedDrive;
        boolean enabled = energy ? energyEnabled : driveEnabled;
        if (!correctionAllowed || !enabled || !valid(expected) || expected.equals(observedMode)) {
            return Decision.IGNORE;
        }

        if (correctionsThisWake >= MAX_CORRECTIONS_PER_WAKE) return Decision.IGNORE;

        if (lastCorrectionUptime == Long.MIN_VALUE
                || nowUptime - lastCorrectionUptime >= CORRECTION_COOLDOWN_MS) {
            lastCorrectionUptime = nowUptime;
            correctionsThisWake++;
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
