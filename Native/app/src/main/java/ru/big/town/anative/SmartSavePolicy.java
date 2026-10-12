package ru.big.town.anative;

/** Charge hysteresis is independent of speed and survives Native restarts. */
final class SmartSavePolicy {
    private boolean chargingLocked;
    private boolean manualSaveHeld;

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

    SmartSavePolicy(boolean chargingLocked) {
        this.chargingLocked = chargingLocked;
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

        if (charge < target - 5) {
            chargingLocked = true;
        } else if (charge >= target) {
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
