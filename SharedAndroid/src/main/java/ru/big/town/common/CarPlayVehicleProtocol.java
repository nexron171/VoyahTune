package ru.big.town.common;

/** CarPlay vehicle integration over the signature-protected Native Messenger. */
public final class CarPlayVehicleProtocol {
    private CarPlayVehicleProtocol() {}

    public static final int WATCH = 120, UNWATCH = 121, STATE = 122, NAVIGATION = 123;
    public static final int OPEN_PROJECTION = 124, PROJECTION_RESULT = 125;
    public static final String DISPLAY_ID = "displayId", LAUNCHED = "launched";
    public static final String NAV_ACTIVE = "navigationActive",
            NAV_ICON = "navigationIcon",
            NAV_DISTANCE = "navigationDistance",
            NAV_ROAD = "navigationRoad",
            NAV_TIME = "navigationTime";
    public static final String SPEED_KMH = "speedKmh",
            GEAR = "gear",
            SW_REASON = "swReason",
            SPEED_TIME = "speedTime";
    public static final String SIRI_ACTION = "carplay_siri";
    public static final String SIRI_BROADCAST = "ru.big.town.restoremode.CARPLAY_SIRI";
    public static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
}
