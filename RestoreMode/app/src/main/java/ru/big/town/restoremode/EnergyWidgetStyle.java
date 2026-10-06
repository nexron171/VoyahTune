package ru.big.town.restoremode;

import java.util.Locale;

/** Common palette and estimated readings for the dashboard and trip history. */
final class EnergyWidgetStyle {
    static final int WHITE = 0xffeef1f6, MUTED = 0xffaab3c4, GREEN = 0xff66d3ad,
            BLUE = 0xff79b5f1, BORDER = 0xff373f4a;
    static final float LINE_WIDTH = 3;
    private static final Locale RU = new Locale("ru", "RU");
    private EnergyWidgetStyle() { }
    static String estimate(double value) {
        return Double.isFinite(value) ? "~" + String.format(RU, "%.1f", value) : "—";
    }
}
