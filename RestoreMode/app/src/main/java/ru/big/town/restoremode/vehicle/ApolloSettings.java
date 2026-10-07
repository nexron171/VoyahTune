package ru.big.town.restoremode.vehicle;

import android.content.SharedPreferences;

/** Persisted Apollo targets owned by VoyahTune; no live CAN state is mirrored into these values. */
public final class ApolloSettings {
    public static final String STOCK_UI = "apolloStockUiEnabled";
    public static final String TLC = "apolloTlcEnabled";
    public static final String TRAFFIC_LIGHTS = "apolloTrafficLightsEnabled";
    public static final String GREEN_SOUND = "apolloGreenSoundEnabled";
    public static final String TRAFFIC_SIGNS = "apolloTrafficSignsEnabled";

    public static final String SPEED_SIGNS = "apolloSpeedSignsEnabled";

    public static final String CRUISE_SPEED_ADJUSTMENT = "apolloCruiseSpeedAdjustmentEnabled";
    public static final String SPEED_MODE = "apolloSpeedMode";
    public static final String SPEED_WARNING = "apolloSpeedWarningEnabled";
    public static final int RECOGNITION_ONLY = 4;
    public static final int CONFIRM_CORRECTION = 3;
    public static final int AUTO_CORRECTION = 2;

    public static final boolean DEFAULT_ENABLED = false;

    public static int speedMode(SharedPreferences prefs) {
        int mode =
                prefs.getInt(
                        SPEED_MODE,
                        prefs.getBoolean(CRUISE_SPEED_ADJUSTMENT, false)
                                ? AUTO_CORRECTION
                                : RECOGNITION_ONLY);
        return mode == AUTO_CORRECTION || mode == CONFIRM_CORRECTION ? mode : RECOGNITION_ONLY;
    }

    private ApolloSettings() {}
}
