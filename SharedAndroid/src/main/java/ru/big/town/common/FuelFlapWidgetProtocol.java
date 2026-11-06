package ru.big.town.common;

/** Fuel flap and refill estimate over the signature-protected Native Messenger. */
public final class FuelFlapWidgetProtocol {
    public static final int WATCH = 200;
    public static final int UNWATCH = 201;
    public static final int OPEN = 202;
    public static final int STATE = 203;

    public static final String CAN_OPEN = "canOpen";
    public static final String REFILL_LITERS = "refillLiters";
    public static final String CAPACITY_LITERS = "capacityLiters";
    public static final String REMAINING_PERCENT = "remainingPercent";
    public static final String MESSAGE = "message";

    private FuelFlapWidgetProtocol() {}
}
