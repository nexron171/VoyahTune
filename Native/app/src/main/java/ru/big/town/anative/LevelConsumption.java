package ru.big.town.anative;

import ru.big.town.common.EnergyWidgetSettings;

/** A sensor's downward steps and observed distance, independent of tank/battery capacity. */
final class LevelConsumption {
    private double decrease, distance;
    private double lastKm = Double.NaN;
    private float lastLevel = Float.NaN;

    void clear() { decrease = distance = 0; breakSegment(); }
    void breakSegment() { lastKm = Double.NaN; lastLevel = Float.NaN; }
    void observe(double km, float level) {
        if (!Double.isFinite(km) || km < 0 || !Float.isFinite(level) || level < 0 || level > 100) {
            breakSegment(); return;
        }
        if (Double.isFinite(lastKm) && km >= lastKm && km - lastKm <= .51) {
            decrease += Math.max(0, lastLevel - level);
            distance += km - lastKm;
        }
        lastKm = km; lastLevel = level;
    }
    double decrease() { return decrease; }
    double distance() { return distance; }
    float average(float capacity) { return EnergyWidgetSettings.average(decrease, distance, capacity); }
    double[] snapshot() { return new double[]{decrease, distance}; }
    void restore(double[] state) {
        clear();
        if (state == null || state.length != 2) return;
        for (double v : state) if (!Double.isFinite(v) || v < 0) return;
        decrease = state[0]; distance = state[1];
        // Previous sensor level is intentionally not restored: never bridge unobserved time.
    }
}
