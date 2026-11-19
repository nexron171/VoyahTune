package ru.big.town.common;

import java.util.Arrays;
import java.util.List;

public enum DockIconStyle {
    DEFAULT("default"),
    NAVIGATION("navigation"),
    MUSIC("music"),
    CAR("car"),
    CLIMATE("climate"),
    APPS("apps");

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

    /** Driver slots replace the OEM navigation and media buttons, so only their artwork is offered. */
    public static List<DockIconStyle> driverStyles() {
        return Arrays.asList(DEFAULT, NAVIGATION, MUSIC);
    }

    /**
     * Passenger slots take any app, and OEM packages often ship the platform's placeholder icon
     * instead of their own, so they get the whole artwork set.
     */
    public static List<DockIconStyle> passengerStyles() {
        return Arrays.asList(DEFAULT, NAVIGATION, MUSIC, CAR, CLIMATE, APPS);
    }

    public static String preferenceKey(int slot) {
        return "dockOverride" + slot + "Icon";
    }

    public static String configKey(int slot) {
        return "dock" + slot + "Icon";
    }

    /** Passenger slots keep their own style; the same name is used in settings and the config. */
    public static String passengerKey(int slot) {
        return "passengerDockSlot" + slot + "Icon";
    }
}
