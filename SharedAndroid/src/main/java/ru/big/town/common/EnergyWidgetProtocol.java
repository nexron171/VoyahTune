package ru.big.town.common;

/** Read-only, signature-protected Native → RestoreMode telemetry contract. */
public final class EnergyWidgetProtocol {
    private EnergyWidgetProtocol() {}
    public static final int WATCH = 100, UNWATCH = 101, STATE = 102, VERSION = 1;
    public static final String SCHEMA = "schema", CONNECTED = "connected", UPDATED = "updated",
            INSTANT = "instant", TRIP = "trip", TIRES = "tires", ODOMETER = "odometer",
            HISTORY_X = "historyX", HISTORY_EV = "historyEv", HISTORY_FUEL = "historyFuel",
            HISTORY_BREAK = "historyBreak";
    // Arrays: instant {kWh/100km, L/100km}; trip {km, kWh/100km, L/100km};
    // tires {FL, FR, RL, RR} in bar. NaN means unavailable; zero is never a fallback.
    public static final long UI_TIMEOUT_MS = 15_000L;
}
