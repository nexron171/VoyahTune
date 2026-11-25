package ru.big.town.anative;

/** Keeps startup readiness separate from invalid or expired measurements. */
final class SmartSaveSession extends EnergyModeSession {
    static final long STARTUP_TIMEOUT_MS = 5_000;

    private final SmartSavePolicy policy;
    private final long startedAt;
    private boolean startupFinished;

    SmartSaveSession(SmartSavePolicy policy, long startedAt) {
        this.policy = policy;
        this.startedAt = startedAt;
    }

    @Override
    void recordSpeed(int value) {
        super.recordSpeed(value);
        policy.onSpeedChanged(value);
    }

    @Override
    int desiredMode(long now, int currentMode) {
        boolean chargeValid = validCharge();
        boolean targetValid = VehicleRestorePolicy.validSaveTarget(target);
        boolean chargeFresh = freshCharge(now);
        int desired =
                policy.desiredMode(chargeFresh ? charge : Float.NaN, target, speed, currentMode);

        if ((chargeReceived && (!chargeValid || !chargeFresh))
                || (targetReceived && !targetValid)
                || (speedReceived && speed < 0)) {
            startupFinished = true;
            reason =
                    chargeReceived && chargeValid && !chargeFresh ? "stale charge" : "invalid data";
            return VehicleRestorePolicy.SOC_SREV;
        }
        if (chargeReceived && targetReceived && speedReceived) {
            startupFinished = true;
        }
        if (policy.isManualSaveHeld()) {
            reason = "manual Save hold";
            return VehicleRestorePolicy.SOC_SREV;
        }
        if (policy.isChargingLocked()) {
            reason = "charge lock";
            return VehicleRestorePolicy.SOC_SREV;
        }
        if (!chargeReceived || !targetReceived || !speedReceived) {
            if (!startupFinished && now - startedAt < STARTUP_TIMEOUT_MS) {
                reason = "waiting for initial data";
                return WAIT_FOR_DATA;
            }
            startupFinished = true;
            reason = "initial data unavailable";
            return VehicleRestorePolicy.SOC_SREV;
        }
        reason = speed < 15 ? "low speed" : speed > 35 ? "high speed" : "speed hysteresis";
        return desired;
    }

    @Override
    long nextEvaluationAt(long now) {
        long deadline = Long.MAX_VALUE;
        if (!startupFinished && startedAt + STARTUP_TIMEOUT_MS > now) {
            deadline = startedAt + STARTUP_TIMEOUT_MS;
        }
        if (chargeReceived && chargeTime >= 0) {
            long expiry = chargeTime + MAX_CHARGE_AGE_MS + 1;
            if (expiry > now) {
                deadline = Math.min(deadline, expiry);
            }
        }
        return deadline == Long.MAX_VALUE ? -1 : deadline;
    }
}
