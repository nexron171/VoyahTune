package ru.big.town.common;

public enum DockIconStyle {
    DEFAULT("default"),
    NAVIGATION("navigation"),
    MUSIC("music");

    public final String value;

    DockIconStyle(String value) {
        this.value = value;
    }

    public static DockIconStyle fromValue(String value) {
        for (DockIconStyle style : values()) {
            if (style.value.equals(value)) {
                return style;
            }
        }
        return DEFAULT;
    }

    public static String preferenceKey(int slot) {
        return "dockOverride" + slot + "Icon";
    }

    public static String configKey(int slot) {
        return "dock" + slot + "Icon";
    }
}
