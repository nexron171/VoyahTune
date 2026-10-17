package ru.big.town.restoremode.widgets.headlights;

public final class HeadlightWidgetLayout {
    public static final String ID = "headlightWidget";
    public static final String VISIBLE = "showHeadlightWidget";
    public static final int DEFAULT_WIDTH = 4;

    private HeadlightWidgetLayout() {}

    public static int width(int value) {
        return Math.max(2, Math.min(4, value));
    }

    public static int height(int columns) {
        return width(columns) == 2 ? 2 : 1;
    }
}
