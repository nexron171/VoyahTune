package ru.big.town.restoremode.widgets.system;

public final class SystemWidgetLayout {
    public static final String CPU = "cpuWidget", RAM = "ramWidget", CLEAR = "clearMemoryWidget";

    private SystemWidgetLayout() {}

    public static boolean isWidget(String id) {
        return CPU.equals(id) || RAM.equals(id) || CLEAR.equals(id);
    }

    public static int width(String id, int value) {
        return CLEAR.equals(id) ? 1 : Math.max(1, Math.min(2, value));
    }
}
