package ru.big.town.anative;

final class HybridEngineSession extends EnergyModeSession {
    // A queued return must survive a reconnect between the two speed thresholds.
    static final int RETURN_PENDING = -2;
    private static final int FUEL_BELOW_KMH = 20;
    private static final int RETURN_TO_HYBRID_KMH = 40;
    private int desired = WAIT_FOR_DATA;
    private int fuelTarget = NO_FUEL_TARGET;

    HybridEngineSession(int ownedFuelTarget) {
        if (VehicleRestorePolicy.validSaveTarget(ownedFuelTarget)) {
            fuelTarget = ownedFuelTarget;
            desired = VehicleRestorePolicy.SOC_SREV;
            reason = "resume temporary fuel";
        } else if (ownedFuelTarget == RETURN_PENDING) {
            desired = VehicleRestorePolicy.SOC_REV;
            reason = "resume return to hybrid";
        }
    }

    @Override
    int desiredMode(long now, int currentMode) {
        if (speed < 0) return WAIT_FOR_DATA;
        if (speed > RETURN_TO_HYBRID_KMH
                || (speed == RETURN_TO_HYBRID_KMH && desired == VehicleRestorePolicy.SOC_SREV)) {
            desired = VehicleRestorePolicy.SOC_REV;
            fuelTarget = NO_FUEL_TARGET;
            reason = "return to hybrid";
        } else if (speed < FUEL_BELOW_KMH
                && fuelTarget == NO_FUEL_TARGET
                && validCharge()
                && freshCharge(now)) {
            int step = VehicleRestorePolicy.SAVE_PERCENT_STEP;
            int rounded = (int) Math.ceil((double) charge / step) * step;
            fuelTarget =
                    Math.max(
                            VehicleRestorePolicy.MIN_SAVE_PERCENT,
                            Math.min(VehicleRestorePolicy.MAX_SAVE_PERCENT, rounded));
            desired = VehicleRestorePolicy.SOC_SREV;
            reason = "low speed, fuel target " + fuelTarget + "%";
        }
        return desired;
    }

    @Override
    int fuelTargetPercent() {
        return fuelTarget;
    }
}
