package ru.big.town.restoremode.widgets.brightness;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.restoremode.integration.config.BrightnessCalibration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class BrightnessCalibrationTest {
    private SharedPreferences preferences;

    @Before
    public void prepare() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("brightnessTest", 0);
        preferences.edit().clear().putBoolean(ScreenBrightnessSettings.ENABLED, true)
                .putString(ScreenBrightnessSettings.RULES, "low,lt,2,20,night\nhigh,ge,5,90").commit();
    }

    @Test
    public void addsMissingLevelAndUpdatesItWithoutChangingOtherLevelsOrNightThreshold() {
        Bundle learned = BrightnessCalibration.learn(preferences, 3, 47);
        String encoded = learned.getString(ScreenBrightnessSettings.RULES);
        assertEquals(47, ScreenBrightnessSettings.matchingRule(ScreenBrightnessSettings.decode(encoded), 3).percent);
        assertEquals(20, ScreenBrightnessSettings.matchingRule(ScreenBrightnessSettings.decode(encoded), 0).percent);
        assertEquals(90, ScreenBrightnessSettings.matchingRule(ScreenBrightnessSettings.decode(encoded), 7).percent);
        assertEquals(1, preferences.getInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, -1));
        BrightnessCalibration.learn(preferences, 3, 63);
        String saved = preferences.getString(ScreenBrightnessSettings.RULES, "");
        assertEquals(63, ScreenBrightnessSettings.matchingRule(ScreenBrightnessSettings.decode(saved), 3).percent);
        assertEquals(6, ScreenBrightnessSettings.decode(saved).size());
    }

    @Test
    public void disabledAdaptiveOrRulesAndInvalidMeasurementsDoNotChangeSavedSettings() {
        String previous = preferences.getString(ScreenBrightnessSettings.RULES, "");
        preferences.edit().putBoolean(ScreenBrightnessSettings.ADAPTIVE, false).commit();
        assertTrue(BrightnessCalibration.learn(preferences, 3, 50).isEmpty());
        preferences.edit().putBoolean(ScreenBrightnessSettings.ADAPTIVE, true)
                .putBoolean(ScreenBrightnessSettings.ENABLED, false).commit();
        assertTrue(BrightnessCalibration.learn(preferences, 3, 50).isEmpty());
        preferences.edit().putBoolean(ScreenBrightnessSettings.ENABLED, true).commit();
        assertTrue(BrightnessCalibration.learn(preferences, -1, 50).isEmpty());
        assertTrue(BrightnessCalibration.learn(preferences, 8, 50).isEmpty());
        assertTrue(BrightnessCalibration.learn(preferences, 3, 0).isEmpty());
        assertTrue(BrightnessCalibration.learn(preferences, 3, 101).isEmpty());
        assertEquals(previous, preferences.getString(ScreenBrightnessSettings.RULES, ""));
    }

    @Test
    public void migrationUsesFirstLegacyMatchAndExtractsLegacyNightThreshold() {
        String legacy = "first,ge,2,40\nsecond,ge,5,90\nlast,lt,2,10,night";
        java.util.List<ScreenBrightnessSettings.Rule> levels = ScreenBrightnessSettings.levels(legacy);
        assertEquals(8, levels.size());
        assertEquals(40, ScreenBrightnessSettings.matchingRule(levels, 6).percent);
        assertEquals(1, ScreenBrightnessSettings.nightThreshold(legacy));
        assertFalse(levels.get(0).night);

    }
}
