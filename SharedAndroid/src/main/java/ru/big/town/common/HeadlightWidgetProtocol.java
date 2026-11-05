package ru.big.town.common;

/** Exterior-light selection over the signature-protected Native Messenger. */
public final class HeadlightWidgetProtocol {
    public static final int WATCH = 130;
    public static final int UNWATCH = 131;
    public static final int SELECT = 132;
    public static final int STATE = 133;

    public static final int UNKNOWN = -1;
    public static final int OFF = 0;
    public static final int PARKING = 1;
    public static final int LOW_BEAM = 2;
    public static final int AUTO = 3;
    public static final int REAR_FOG = 4;

    public static final String FOG_STATE = "fogState";
    public static final String FOG_PENDING = "fogPending";
    public static final String FOG_AVAILABLE = "fogAvailable";
    public static final String FOG_MESSAGE = "fogMessage";

    public static final String MODE = "mode";
    public static final String PENDING = "pending";
    public static final String MESSAGE = "message";
    public static final String AVAILABLE = "available";

    private HeadlightWidgetProtocol() {}

    public static boolean validMode(int mode) {
        return mode >= OFF && mode <= AUTO;
    }

    public static String action(int mode) {
        switch (mode) {
            case OFF:
                return "headlights:off";
            case PARKING:
                return "headlights:parking";
            case LOW_BEAM:
                return "headlights:on";
            case AUTO:
                return "headlights:auto";
            default:
                return null;
        }
    }
}
