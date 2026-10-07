package ru.big.town.restoremode.widgets.energy;

import ru.big.town.common.EnergyWidgetSettings;

/** A fixed distance window, labelled backwards from the current rounded odometer. */
final class EnergyChartAxis {
    static final int INTERVALS = 5;
    final double end;
    final int window;
    private final double odometer;

    EnergyChartAxis(double end, int window, float odometer) {
        this.end = Double.isFinite(end) && end >= 0 ? end : 0;
        this.window = EnergyWidgetSettings.window(window);
        this.odometer =
                Float.isFinite(odometer) && odometer > 0
                        ? Math.round((double) odometer)
                        : Double.NaN;
    }

    float position(double km) {
        return (float) (1 + (km - end) / window);
    }

    double distanceAt(float position) {
        return end - window + position * window;
    }

    double odometerAt(double km) {
        double value = odometer - (end - km);
        return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }

    double tick(int fromRight) {
        return odometerAt(end - (double) window * fromRight / INTERVALS);
    }
}
