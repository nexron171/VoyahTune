package ru.big.town.restoremode.widgets.fuel;

public enum FuelFlapWidgetLayout {
    COMPACT(1, 1),
    STANDARD(2, 1),
    WIDE(3, 1),
    TALL(1, 2),
    LARGE(2, 2);

    public static final String ID = "fuelFlapWidget";
    public static final String VISIBLE = "showFuelFlapWidget";
    public final int columns;
    public final int rows;

    FuelFlapWidgetLayout(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public static FuelFlapWidgetLayout fit(int columns, int rows) {
        if (columns <= 1) return rows <= 1 ? COMPACT : TALL;
        if (columns == 2) return rows <= 1 ? STANDARD : LARGE;
        return WIDE;
    }
}
