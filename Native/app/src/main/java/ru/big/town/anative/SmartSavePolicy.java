package ru.big.town.anative;

/** The persisted charge lock requires charge recovery and driving speed to release. */
final class SmartSavePolicy {
    private static final float CHARGE_LOCK_DEFICIT_PERCENT = 5f;
    private static final float CHARGE_UNLOCK_DEFICIT_PERCENT = 2.5f;
    private static final int CHARGE_UNLOCK_SPEED_KMH = 35;

    private boolean chargingLocked;
    private boolean manualSaveHeld;

    SmartSavePolicy(boolean chargingLocked) {
        this.chargingLocked = chargingLocked;
    }

    void holdManualSave() {
        manualSaveHeld = true;
    }

    void onSpeedChanged(int speed) {
        if (speed > 35) {
            manualSaveHeld = false;
        }
    }

    boolean isManualSaveHeld() {
        return manualSaveHeld;
    }

    boolean isChargingLocked() {
        return chargingLocked;
    }

    int desiredMode(float charge, int target, int speed, int currentMode) {
        if (!Float.isFinite(charge)
                || charge < 0
                || charge > 100
                || target < 25
                || target > 80
                || target % 5 != 0) {
            return VehicleRestorePolicy.SOC_SREV;
        }

        if (charge <= target - CHARGE_LOCK_DEFICIT_PERCENT) {
            chargingLocked = true;
        } else if (charge >= target - CHARGE_UNLOCK_DEFICIT_PERCENT
                && speed >= CHARGE_UNLOCK_SPEED_KMH) {
            chargingLocked = false;
        }

        if (manualSaveHeld || chargingLocked || speed < 0 || speed > 35) {
            return VehicleRestorePolicy.SOC_SREV;
        }
        if (speed < 15) {
            return VehicleRestorePolicy.SOC_EV;
        }
        return currentMode == VehicleRestorePolicy.SOC_EV
                ? VehicleRestorePolicy.SOC_EV
                : VehicleRestorePolicy.SOC_SREV;
    }
}
