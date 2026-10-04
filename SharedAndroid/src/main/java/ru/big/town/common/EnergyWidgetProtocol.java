package ru.big.town.common;

/** Signature-protected telemetry/settings contract; no vehicle control commands. */
public final class EnergyWidgetProtocol {
    private EnergyWidgetProtocol() {}
    public static final int WATCH = 100, UNWATCH = 101, STATE = 102, CONFIGURE = 103, VERSION = 4;
    public static final String SCHEMA = "schema", CONNECTED = "connected", UPDATED = "updated",
            LEVELS = "levels", TRIP = "trip", TIRES = "tires", ODOMETER = "odometer",
            HISTORY_X = "historyX", HISTORY_EV = "historyEv", HISTORY_FUEL = "historyFuel",
            HISTORY_BREAK = "historyBreak", TRIP_OBSERVED_KM = "tripObservedKm",
            BATTERY_KWH = "batteryKwh", TANK_LITERS = "tankLiters",
            HISTORY_EV_DROP = "historyEvDrop", HISTORY_FUEL_DROP = "historyFuelDrop",
            HISTORY_EV_KM = "historyEvKm", HISTORY_FUEL_KM = "historyFuelKm";
    public static final String HISTORY_START_EV_DROP="historyStartEvDrop", HISTORY_START_FUEL_DROP="historyStartFuelDrop",
            HISTORY_START_EV_KM="historyStartEvKm", HISTORY_START_FUEL_KM="historyStartFuelKm";
    public static final String RECORDED_KM="recordedKm", CONSUMPTION_START="consumptionStart", CONSUMPTION_END="consumptionEnd",
            CONSUMPTION_EV="consumptionEv", CONSUMPTION_FUEL="consumptionFuel", CONSUMPTION_BREAK="consumptionBreak";
    // recordedKm: current continuous observed distance (double). Consumption start/end: double arrays,
    // actual bounds of completed 250 m cadence intervals in the last 10 km. EV/fuel: float arrays,
    // absolute kWh/liters for that interval, NOT per 100 km. EV is signed (negative = recovered charge).
    // Battery rises >5 percentage points invalidate its interval. Refills never subtract fuel use.
    // Invalid channels are NaN; boolean breaks prevent joining unobserved distance/time.
    // float arrays: levels {battery %, fuel %}; trip {OEM km, estimated kWh/100km, estimated L/100km};
    // tripObservedKm {battery observed km, fuel observed km}; capacities are float scalars.
    // historyX is a continuous recorded axis at 100 m steps, preserved between OEM trips.
    // historyEv/historyFuel are levels in %. Double arrays *Drop/*Km are cumulative sensor
    // decreases/observed distances BEFORE decimation, retained across parked replenishment.
    // historyStart* are the baseline when a 100 m bucket first appeared, never overwritten
    // by updates while parked; the selected window must include drops within its first bucket.
    // tires {FL, FR, RL, RR} in bar. NaN means unavailable; zero is never a fallback.
    public static final long UI_TIMEOUT_MS = 15_000L;
}
