package ru.big.town.common;

/** Read-only, signature-protected Native → RestoreMode telemetry contract. */
public final class EnergyWidgetProtocol {
    private EnergyWidgetProtocol() {}
    public static final int WATCH = 100, UNWATCH = 101, STATE = 102, VERSION = 2;
    public static final String SCHEMA = "schema", CONNECTED = "connected", UPDATED = "updated",
            LEVELS = "levels", TRIP = "trip", TIRES = "tires", ODOMETER = "odometer",
            HISTORY_X = "historyX", HISTORY_EV = "historyEv", HISTORY_FUEL = "historyFuel",
            HISTORY_BREAK = "historyBreak", FUEL_ESTIMATE_KM = "fuelEstimateKm";
    // Arrays: levels {battery %, fuel %}; trip {OEM km, OEM kWh/100km, estimated L/100km};
    // historyEv/historyFuel are levels in %, historyX is OEM trip km at 100 m steps.
    // fuelEstimateKm is the observed distance used for the fuel estimate (may be partial).
    // tires {FL, FR, RL, RR} in bar. NaN means unavailable; zero is never a fallback.
    public static final long UI_TIMEOUT_MS = 15_000L;
}
