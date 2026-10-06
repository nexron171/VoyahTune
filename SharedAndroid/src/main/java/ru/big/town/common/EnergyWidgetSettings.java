package ru.big.town.common;

/** Shared units and validation; capacities never change the recorded percentages. */
public final class EnergyWidgetSettings {
    private EnergyWidgetSettings() {}
    public static final String BATTERY_KEY = "energyBatteryKwh", TANK_KEY = "energyTankLiters",
            WINDOW_KEY = "energyWindowKm";
    public static final float DEFAULT_BATTERY_KWH = 43, DEFAULT_TANK_LITERS = 56;
    public static final int DEFAULT_WINDOW_KM = 100, AVERAGE_WINDOW_KM = 100;
    public static final int[] WINDOWS = {50, 100, 150};
    public static boolean validCapacity(float value) { return Float.isFinite(value) && value > 0; }
    public static float parseCapacity(String text) {
        if (text == null) return Float.NaN;
        String normalized = text.trim().replace(',', '.');
        if (!normalized.matches("(?:\\d+(?:\\.\\d)?|\\.\\d)")) return Float.NaN;
        try {
            float value = Float.parseFloat(normalized);
            return validCapacity(value) ? value : Float.NaN;
        } catch (NumberFormatException e) { return Float.NaN; }
    }
    public static int window(int value) {
        if (value == 50 || value == 100 || value == 150) return value;
        return value == 5 || value == 25 ? 50 : value == 30 ? 150 : DEFAULT_WINDOW_KM;
    }
    public static float average(double decreasePercent, double observedKm, float capacity) {
        return decreasePercent < 0 ? Float.NaN : electricityAverage(decreasePercent, observedKm, capacity);
    }
    /** Negative net use means more charge was recovered than spent on the observed distance. */
    public static float electricityAverage(double netPercent, double observedKm, float capacity) {
        if (!Double.isFinite(netPercent) || !Double.isFinite(observedKm) || observedKm < 1 || !validCapacity(capacity)) return Float.NaN;
        double result = netPercent * capacity / observedKm;
        return Double.isFinite(result) && Math.abs(result) <= Float.MAX_VALUE ? (float) result : Float.NaN;
    }
}
