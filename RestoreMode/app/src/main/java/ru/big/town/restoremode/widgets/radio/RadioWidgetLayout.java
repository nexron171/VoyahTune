package ru.big.town.restoremode.widgets.radio;

public final class RadioWidgetLayout {
    public static final String ID = "radioWidget";
    public static final String VISIBLE = "showRadioWidget";
    public static final int DEFAULT_WIDTH = 2;
    public static final int DEFAULT_HEIGHT = 2;

    private RadioWidgetLayout() {}

    public static int width(int columns) {
        return Math.max(2, Math.min(3, columns));
    }

    public static int height(int rows) {
        return Math.max(2, Math.min(5, rows));
    }
}
