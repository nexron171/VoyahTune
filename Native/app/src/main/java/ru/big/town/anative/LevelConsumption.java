package ru.big.town.anative;

import ru.big.town.common.EnergyWidgetSettings;

/** A sensor's observed use and distance, independent of tank/battery capacity. */
final class LevelConsumption {
    private final BatteryConsumption recovery;
    private double decrease, distance;
    private double lastKm = Double.NaN;
    private float lastLevel = Float.NaN;

    LevelConsumption() { this(false); }
    LevelConsumption(boolean electricity) { recovery = electricity ? new BatteryConsumption() : null; }
    void clear() { decrease = distance = 0; breakSegment(); }
    void breakSegment() {
        lastKm = Double.NaN; lastLevel = Float.NaN;
        if (recovery != null) recovery.breakSegment();
    }
    void observe(double km, float level) {
        if (!Double.isFinite(km) || km < 0 || !Float.isFinite(level) || level < 0 || level > 100) {
            breakSegment(); return;
        }
        if (Double.isFinite(lastKm) && km >= lastKm && km - lastKm <= .51) {
            decrease += recovery == null ? Math.max(0, lastLevel - level) : recovery.delta(lastLevel, level);
            distance += km - lastKm;
        } else if (recovery != null) recovery.breakSegment();
        lastKm = km; lastLevel = level;
    }
    double decrease() { return decrease; }
    double distance() { return distance; }
    float average(float capacity) { return recovery == null ? EnergyWidgetSettings.average(decrease, distance, capacity)
            : EnergyWidgetSettings.electricityAverage(decrease, distance, capacity); }
    double[] snapshot() { return new double[]{decrease, distance}; }
    void restore(double[] state) {
        clear();
        if (state == null || state.length != 2) return;
        if (!Double.isFinite(state[0]) || (recovery == null && state[0] < 0)
                || !Double.isFinite(state[1]) || state[1] < 0) return;
        decrease = state[0]; distance = state[1];
        // Previous sensor level is intentionally not restored: never bridge unobserved time.
    }
}
