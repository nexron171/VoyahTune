package ru.big.town.anative;

/** Android-free timing policy. A persisted elapsed clock is usable only in the same boot. */
final class TripSession {
    static final long PAUSE_TIMEOUT_MS = 60 * 60 * 1000L;
    static final long MIN_HISTORY_MS = 5 * 60 * 1000L;
    static final double MOVEMENT_RESTART_METERS = 10;
    boolean active, inDrive;
    boolean waitingForMovement;
    double restartOdo = Double.NaN;
    int restartOdometerSource;
    long startWall, accumulatedMs, driveStartElapsed, pausedWall, checkpointWall, checkpointElapsed;
    int bootCount;
    private boolean recoveredDrive, sameBoot;
    private int currentBoot = Integer.MIN_VALUE;

    void recover(int currentBoot) {
        this.currentBoot = currentBoot;
        recoveredDrive = active && inDrive;
        sameBoot = currentBoot >= 0 && bootCount == currentBoot;
        if (recoveredDrive) pausedWall = checkpointWall;
        inDrive = false;
        driveStartElapsed = 0;
    }

    boolean expiresOnDrive(long wall) {
        return active && !inDrive && !(recoveredDrive && sameBoot) && pausedWall > 0
                && wall > pausedWall && wall - pausedWall > PAUSE_TIMEOUT_MS;
    }
    static boolean doorOpening(boolean live, int previous, int current) {
        return live && previous == 0 && current == 1;
    }
    static boolean shouldSaveHistory(boolean enabled, boolean manualStop, long durationMs) {
        return enabled && durationMs >= 0 && (manualStop || durationMs >= MIN_HISTORY_MS);
    }

    void waitForMovement(double odometer, int source) {
        waitingForMovement = true;
        restartOdo = source > 0 && Double.isFinite(odometer) && odometer > 0 ? odometer : Double.NaN;
        restartOdometerSource = Double.isFinite(restartOdo) ? source : 0;
    }
    void cancelMovementRestart() {
        waitingForMovement = false; restartOdo = Double.NaN; restartOdometerSource = 0;
    }
    boolean allowsDriveStart(boolean live, int previousGear) {
        // The same D after a manual stop, including startup snapshots, is not a new D edge.
        return !waitingForMovement || live && previousGear >= 0 && previousGear != 3;
    }
    boolean movementRestart(double odometer, int source) {
        if (!waitingForMovement || active || source <= 0 || !Double.isFinite(odometer) || odometer <= 0) return false;
        if (source != restartOdometerSource || !Double.isFinite(restartOdo) || odometer < restartOdo) {
            waitForMovement(odometer, source); return false;
        }
        return odometer - restartOdo >= MOVEMENT_RESTART_METERS;
    }
    TripSession afterFinish() {
        TripSession idle = new TripSession();
        idle.bootCount = bootCount; idle.currentBoot = currentBoot;
        idle.checkpointWall = checkpointWall; idle.checkpointElapsed = checkpointElapsed;
        idle.waitingForMovement = waitingForMovement;
        idle.restartOdo = restartOdo; idle.restartOdometerSource = restartOdometerSource;
        return idle;
    }

    void gear(boolean drive, long wall, long elapsed) {
        if (drive) {
            if (inDrive) return;
            cancelMovementRestart();
            if (!active) { active = true; startWall = wall; accumulatedMs = 0; }
            // Restart in this boot and confirmed D: preserve the uninterrupted interval.
            if (recoveredDrive && sameBoot && elapsed >= checkpointElapsed)
                accumulatedMs += elapsed - checkpointElapsed;
            inDrive = true; driveStartElapsed = elapsed; pausedWall = 0;
        } else if (inDrive) {
            accumulatedMs = duration(elapsed); inDrive = false; pausedWall = wall;
        } else if (active && pausedWall == 0) pausedWall = wall;
        recoveredDrive = false;
        if (currentBoot != Integer.MIN_VALUE) bootCount = currentBoot;
    }

    long duration(long elapsed) {
        return accumulatedMs + (inDrive ? Math.max(0, elapsed - driveStartElapsed) : 0);
    }

    void checkpoint(long wall, long elapsed) {
        // While awaiting the first fresh gear, keep the saved clock and its original boot.
        if (recoveredDrive) return;
        accumulatedMs = duration(elapsed);
        if (inDrive) driveStartElapsed = elapsed;
        checkpointWall = wall; checkpointElapsed = elapsed;
    }
    boolean persistedDrive() { return inDrive || recoveredDrive; }
}
