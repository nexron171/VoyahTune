package ru.big.town.restoremode.widgets.energy;

import ru.big.town.common.EnergyWidgetSettings;

/** Differences of cumulative use: battery includes bounded recovery, fuel only downward steps. */
final class EnergyPeriodEstimate {
    final float battery, fuel, batteryKm, fuelKm;

    private EnergyPeriodEstimate(float battery, float fuel, float batteryKm, float fuelKm) {
        this.battery = battery;
        this.fuel = fuel;
        this.batteryKm = batteryKm;
        this.fuelKm = fuelKm;
    }

    static EnergyPeriodEstimate recent(
            float[] x,
            double[] evDrop,
            double[] fuelDrop,
            double[] evKm,
            double[] fuelKm,
            float battery,
            float tank,
            double[]... baselines) {
        return calculate(
                EnergyWidgetSettings.AVERAGE_WINDOW_KM,
                x,
                evDrop,
                fuelDrop,
                evKm,
                fuelKm,
                battery,
                tank,
                baselines);
    }

    static EnergyPeriodEstimate calculate(
            int window,
            float[] x,
            double[] evDrop,
            double[] fuelDrop,
            double[] evKm,
            double[] fuelKm,
            float battery,
            float tank,
            double[]... baselines) {
        if (x == null || x.length < 2 || !sameLength(x, evDrop, fuelDrop, evKm, fuelKm)) {
            return unavailable();
        }
        if (baselines.length != 0 && (baselines.length != 4 || !sameLength(x, baselines))) {
            return unavailable();
        }
        int end = x.length - 1, start = 0;
        if (!Float.isFinite(x[end])) {
            return unavailable();
        }
        while (start < end && x[start] < x[end] - window - .001f) {
            start++;
        }
        if (start == end) {
            return unavailable();
        }
        double evDistance = evKm[end] - (baselines.length == 0 ? evKm : baselines[2])[start];
        double fuelDistance = fuelKm[end] - (baselines.length == 0 ? fuelKm : baselines[3])[start];
        return new EnergyPeriodEstimate(
                EnergyWidgetSettings.electricityAverage(
                        evDrop[end] - (baselines.length == 0 ? evDrop : baselines[0])[start],
                        evDistance,
                        battery),
                EnergyWidgetSettings.average(
                        fuelDrop[end] - (baselines.length == 0 ? fuelDrop : baselines[1])[start],
                        fuelDistance,
                        tank),
                validDistance(evDistance),
                validDistance(fuelDistance));
    }

    private static float validDistance(double value) {
        return Double.isFinite(value) && value >= 0 ? (float) value : Float.NaN;
    }

    private static boolean sameLength(float[] x, double[]... arrays) {
        for (double[] a : arrays) {
            if (a == null || a.length != x.length) {
                return false;
            }
        }
        return true;
    }

    private static EnergyPeriodEstimate unavailable() {
        return new EnergyPeriodEstimate(Float.NaN, Float.NaN, 0, 0);
    }
}
