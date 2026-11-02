package ru.big.town.restoremode.integration.config;

import android.content.SharedPreferences;
import android.os.Bundle;
import ru.big.town.common.ScreenBrightnessSettings;

/** Persists calibration in the same settings store used by the editor. */
public final class BrightnessCalibration {
    private BrightnessCalibration() {}

    public static Bundle learn(SharedPreferences preferences, int sensor, int percent) {
        Bundle result = new Bundle();
        if (!preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false)
                || !preferences.getBoolean(ScreenBrightnessSettings.ADAPTIVE, true)
                || sensor < 0 || sensor > 7 || percent < 1 || percent > 100) return result;
        String previous = preferences.getString(ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.DEFAULT_RULES);
        String learned = ScreenBrightnessSettings.calibrate(previous, sensor, percent);
        int threshold = preferences.getInt(ScreenBrightnessSettings.NIGHT_THRESHOLD,
                ScreenBrightnessSettings.nightThreshold(previous));
        boolean saved = preferences.edit().putString(ScreenBrightnessSettings.RULES, learned)
                .putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, threshold).commit();
        if (saved) {
            result.putString(ScreenBrightnessSettings.RULES, learned);
            result.putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, threshold);
        }
        return result;
    }
}
