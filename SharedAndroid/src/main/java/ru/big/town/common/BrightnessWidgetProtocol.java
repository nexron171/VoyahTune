package ru.big.town.common;

/** Display modes over the signature-protected Native Messenger. */
public final class BrightnessWidgetProtocol {
    public static final int WATCH = 160;
    public static final int UNWATCH = 161;
    public static final int SELECT = 162;
    public static final int STATE = 163;
    public static final String MODE = "mode";
    public static final String PENDING = "pending";
    public static final String AVAILABLE = "available";
    public static final String MESSAGE = "message";
    public static final String OVERRIDE = "screenBrightnessModeOverride";
    public static final int NIGHT = 0;
    public static final int DAY = 1;
    public static final int AUTO = 2;

    private BrightnessWidgetProtocol() {}

    public static boolean rulesActive(boolean enabled, int mode) {
        return enabled && mode == AUTO;
    }

    public static boolean paused(int mode) {
        return mode == DAY || mode == NIGHT;
    }
}
