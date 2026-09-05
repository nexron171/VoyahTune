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
    private String expectedRecycle;
    private boolean driveEnabled;
    private boolean energyEnabled;
    private boolean recycleEnabled;
    private boolean driveRememberLast = true;
    private boolean energyRememberLast = true;
    private boolean recycleRememberLast = true;

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
        return canPersist(candidateGeneration, "driveMode", nowUptime);
    }

    /** Revalidates both the wake token and the per-mode remember-last opt-out. */
    synchronized boolean canPersist(long candidateGeneration, String modeKey, long nowUptime) {
        return candidateGeneration == generation
                && restoreCompleted
                && nowUptime >= acceptAfterUptime
                && acceptsExternalFeedback(modeKey);
    }

    /** Refreshes the source-of-truth snapshot loaded from RestoreMode/provider or Native cache. */
    synchronized void updateExpected(String drive, String energy,
                                     boolean driveEnabled, boolean energyEnabled) {
        updateExpected(drive, energy, expectedRecycle,
                driveEnabled, energyEnabled, recycleEnabled,
                driveRememberLast, energyRememberLast, recycleRememberLast);
    }

    /** Refreshes the complete source-of-truth snapshot and per-mode feedback preferences. */
    synchronized void updateExpected(String drive, String energy, String recycle,
                                     boolean driveEnabled, boolean energyEnabled,
                                     boolean recycleEnabled,
                                     boolean driveRememberLast, boolean energyRememberLast,
                                     boolean recycleRememberLast) {
        if (valid(drive)) expectedDrive = drive;
        if (valid(energy)) expectedEnergy = energy;
        if (valid(recycle)) expectedRecycle = recycle;
        this.driveEnabled = driveEnabled;
        this.energyEnabled = energyEnabled;
        this.recycleEnabled = recycleEnabled;
        this.driveRememberLast = driveRememberLast;
        this.energyRememberLast = energyRememberLast;
        this.recycleRememberLast = recycleRememberLast;
    }

    /** Updates one explicitly saved mode immediately (steering button or accepted external change). */
    synchronized void updateExpectedMode(boolean energy, String mode) {
        updateExpectedMode(energy ? "energy" : "driveMode", mode);
    }

    synchronized void updateExpectedMode(String modeKey, String mode) {
        if (!valid(mode)) return;
        if ("energy".equals(modeKey)) expectedEnergy = mode;
        else if ("recycle".equals(modeKey)) expectedRecycle = mode;
        else if ("driveMode".equals(modeKey)) expectedDrive = mode;
    }

    /** Applies an explicit UI opt-out without waiting for the next full provider reload. */
    synchronized void updateRememberLast(String modeKey, boolean rememberLast) {
        if ("driveMode".equals(modeKey)) driveRememberLast = rememberLast;
        else if ("energy".equals(modeKey)) energyRememberLast = rememberLast;
        else if ("recycle".equals(modeKey)) recycleRememberLast = rememberLast;
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
        return evaluate(energy ? "energy" : "driveMode", observedMode, nowUptime);
    }

    synchronized Decision evaluate(String modeKey, String observedMode, long nowUptime) {
        if (!valid(observedMode)) return Decision.IGNORE;
        if (!knownModeKey(modeKey)) return Decision.IGNORE;
        // Snow owns minimum recuperation; that safety-derived value is not a user selection and
        // must neither replace the stored recuperation target nor trigger an impossible correction.
        if ("recycle".equals(modeKey) && "SNOW".equals(expectedDrive)) {
            return Decision.IGNORE;
        }
        // The car is a valid source of truth only after the wake-default window has elapsed.
        if (restoreCompleted && nowUptime >= acceptAfterUptime) {
            return acceptsExternalFeedback(modeKey) ? Decision.ACCEPT : Decision.IGNORE;
        }

        String expected = expected(modeKey);
        boolean enabled = enabled(modeKey);
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

    private String expected(String modeKey) {
        if ("energy".equals(modeKey)) return expectedEnergy;
        if ("recycle".equals(modeKey)) return expectedRecycle;
        return expectedDrive;
    }

    private boolean enabled(String modeKey) {
        if ("energy".equals(modeKey)) return energyEnabled;
        if ("recycle".equals(modeKey)) return recycleEnabled;
        return driveEnabled;
    }

    private boolean remembers(String modeKey) {
        if ("energy".equals(modeKey)) return energyRememberLast;
        if ("recycle".equals(modeKey)) return recycleRememberLast;
        return "driveMode".equals(modeKey) && driveRememberLast;
    }

    private boolean acceptsExternalFeedback(String modeKey) {
        return remembers(modeKey)
                && !("recycle".equals(modeKey) && "SNOW".equals(expectedDrive));
    }

    private static boolean knownModeKey(String modeKey) {
        return "driveMode".equals(modeKey)
                || "energy".equals(modeKey)
                || "recycle".equals(modeKey);
    }

    private static boolean valid(String mode) {
        return mode != null && !mode.isEmpty();
    }

    private static long saturatedAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
