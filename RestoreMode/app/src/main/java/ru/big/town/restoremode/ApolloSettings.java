package ru.big.town.restoremode;

import android.content.SharedPreferences;

/** Persisted Apollo targets owned by VoyahTune; no live CAN state is mirrored into these values. */
final class ApolloSettings {
    static final String STOCK_UI = "apolloStockUiEnabled";
    static final String TLC = "apolloTlcEnabled";
    static final String TRAFFIC_LIGHTS = "apolloTrafficLightsEnabled";
    static final String GREEN_SOUND = "apolloGreenSoundEnabled";
    static final String TRAFFIC_SIGNS = "apolloTrafficSignsEnabled";

    static final String SPEED_SIGNS = "apolloSpeedSignsEnabled";

    static final String CRUISE_SPEED_ADJUSTMENT = "apolloCruiseSpeedAdjustmentEnabled";
    static final String SPEED_MODE = "apolloSpeedMode";
    static final String SPEED_WARNING = "apolloSpeedWarningEnabled";
    static final int RECOGNITION_ONLY = 4;
    static final int CONFIRM_CORRECTION = 3;
    static final int AUTO_CORRECTION = 2;

    static final boolean DEFAULT_ENABLED = false;

    static int speedMode(SharedPreferences prefs) {
        int mode = prefs.getInt(SPEED_MODE, prefs.getBoolean(CRUISE_SPEED_ADJUSTMENT, false)
                ? AUTO_CORRECTION : RECOGNITION_ONLY);
        return mode == AUTO_CORRECTION || mode == CONFIRM_CORRECTION
                ? mode : RECOGNITION_ONLY;
    }

    private ApolloSettings() {
    }
}
