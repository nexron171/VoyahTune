package ru.big.town.anative;

/** Keeps startup readiness separate from invalid or expired measurements. */
final class SmartSaveSession {
    static final int WAIT_FOR_DATA = -1;
    static final long STARTUP_TIMEOUT_MS = 5_000;
    static final long MAX_CHARGE_AGE_MS = 90_000;

    private final SmartSavePolicy policy;
    private final long startedAt;
    private boolean startupFinished;
    private boolean chargeReceived;
    private boolean targetReceived;
    private boolean speedReceived;
    private String reason = "waiting for initial data";

    private float charge = Float.NaN;
    private long chargeTime = -1;
    private int target = -1;
    private int speed = -1;

    SmartSaveSession(SmartSavePolicy policy, long startedAt) {
        this.policy = policy;
        this.startedAt = startedAt;
    }

    void recordCharge(float value, long sampledAt) {
        chargeReceived = true;
        charge = value;
        chargeTime = sampledAt;
    }

    void recordTarget(int value) {
        targetReceived = true;
        target = value;
    }

    void recordSpeed(int value) {
        speedReceived = true;
        speed = value;
        policy.onSpeedChanged(value);
    }

    int desiredMode(long now, int currentMode) {
        long age = now - chargeTime;
        boolean chargeValid = Float.isFinite(charge) && charge >= 0 && charge <= 100;
        boolean targetValid = target >= 25 && target <= 80 && target % 5 == 0;
        boolean chargeFresh = chargeTime >= 0 && age >= 0 && age <= MAX_CHARGE_AGE_MS;
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

    String reason() {
        return reason;
    }

    String measurements(long now) {
        return "soc=" + charge + " age=" + (chargeTime < 0 ? -1 : now - chargeTime)
                + " target=" + target + " speed=" + speed;
    }
}
