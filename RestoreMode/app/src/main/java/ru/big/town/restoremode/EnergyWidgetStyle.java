package ru.big.town.restoremode;

import java.util.Locale;

/** Common palette, small-chart typography and estimated dashboard/trip readings. */
final class EnergyWidgetStyle {
    static final int WHITE = 0xffeef1f6, MUTED = 0xffaab3c4, GREEN = 0xff66d3ad,
            BLUE = 0xff79b5f1, BORDER = 0xff373f4a;
    static final float LINE_WIDTH = 3;
    static final float CHART_TITLE_SP = 24, CHART_UNIT_SP = 18, CHART_AXIS_SP = 16,
            CHART_MESSAGE_SP = 18;
    private static final Locale RU = new Locale("ru", "RU");
    private EnergyWidgetStyle() { }
    static String estimate(double value) {
        return Double.isFinite(value) ? "~" + String.format(RU, "%.1f", value) : "—";
    }
}
