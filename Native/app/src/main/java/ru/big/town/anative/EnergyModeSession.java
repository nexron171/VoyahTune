package ru.big.town.anative;

/** Measurements belong to a single selection and CAN connection. */
abstract class EnergyModeSession {
    static final int WAIT_FOR_DATA = -1;
    static final int NO_FUEL_TARGET = -1;
    static final long MAX_CHARGE_AGE_MS = 90_000;

    boolean chargeReceived;
    boolean targetReceived;
    boolean speedReceived;
    float charge = Float.NaN;
    long chargeTime = -1;
    int target = NO_FUEL_TARGET;
    int speed = -1;
    String reason = "waiting for initial data";

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
    }

    boolean validCharge() {
        return Float.isFinite(charge) && charge >= 0 && charge <= 100;
    }

    boolean freshCharge(long now) {
        return chargeTime >= 0 && now >= chargeTime && now - chargeTime <= MAX_CHARGE_AGE_MS;
    }

    abstract int desiredMode(long now, int currentMode);

    int fuelTargetPercent() {
        return NO_FUEL_TARGET;
    }

    long nextEvaluationAt(long now) {
        return -1;
    }

    String reason() {
        return reason;
    }

    String measurements(long now) {
        return "soc="
                + charge
                + " age="
                + (chargeTime < 0 ? -1 : now - chargeTime)
                + " target="
                + target
                + " speed="
                + speed;
    }
}
