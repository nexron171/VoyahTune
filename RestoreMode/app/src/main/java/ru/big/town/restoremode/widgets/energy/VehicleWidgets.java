package ru.big.town.restoremode.widgets.energy;

import android.content.Context;

/** Stable dashboard identifiers and construction of vehicle cards. */
public final class VehicleWidgets {
    private VehicleWidgets() {}

    public static final String ENERGY = "energyWidget",
            CONSUMPTION = "energyConsumptionWidget",
            TRIP = "energyTripWidget",
            TIRES = "tirePressureWidget",
            ODO = "odometerWidget";
    public static final String[] IDS = {ENERGY, CONSUMPTION, TRIP, TIRES, ODO};

    public static boolean isWidget(String id) {
        for (String s : IDS) {
            if (s.equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static int[] size(String id) {
        if (ENERGY.equals(id)) {
            return new int[] {8, 4};
        }
        if (CONSUMPTION.equals(id)) {
            return new int[] {2, 2};
        }
        if (TRIP.equals(id)) {
            return new int[] {8, 2};
        }
        if (TIRES.equals(id)) {
            return new int[] {4, 4};
        }
        return new int[] {4, 1};
    }

    public static VehicleWidgetView create(
            Context context, String widgetId, String carColor, int columns, int rows) {
        switch (widgetId) {
            case ENERGY:
                return new EnergyWidgetView(context, columns, rows);
            case CONSUMPTION:
                return new ConsumptionWidgetView(context, columns, rows);
            case TRIP:
                return new TripWidgetView(context, columns, rows);
            case TIRES:
                return new TirePressureWidgetView(context, carColor, columns, rows);
            case ODO:
                return new OdometerWidgetView(context, columns, rows);
            default:
                throw new IllegalArgumentException("Unknown vehicle widget: " + widgetId);
        }
    }
}
