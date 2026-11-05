package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.common.ScreenBrightnessSettings.Rule;

public class ScreenBrightnessSettingsTest {
    @Test
    public void readingExistingValleysRaisesOnlyValuesBelowPreviousMaximum() {
        List<Rule> levels = ScreenBrightnessSettings.levels(
                "one,eq,1,10\ntwo,eq,2,30\nthree,eq,3,10\nfour,eq,4,50");

        assertPercents(levels, 10, 30, 30, 50);
        assertNull(ScreenBrightnessSettings.matchingRule(levels, 0));
        assertNull(ScreenBrightnessSettings.matchingRule(levels, 5));
    }

    @Test
    public void raisingCurrentLevelRaisesFollowingLevelsUntilAlreadyBrighter() {
        String saved = ScreenBrightnessSettings.calibrate(
                "one,eq,1,10\ntwo,eq,2,10\nthree,eq,3,10\nfour,eq,4,50", 2, 30);

        assertPercents(ScreenBrightnessSettings.decode(saved), 10, 30, 30, 50);
        assertEquals(saved, ScreenBrightnessSettings.calibrate(saved, 2, 30));
    }

    @Test
    public void loweringCurrentLevelLowersEarlierLevelsAndPreservesSelectedValue() {
        String saved = ScreenBrightnessSettings.calibrate(
                "one,eq,1,10\ntwo,eq,2,30\nthree,eq,3,40\nfour,eq,4,50", 3, 20);

        assertPercents(ScreenBrightnessSettings.decode(saved), 10, 20, 20, 50);
    }

    @Test
    public void addingLevelPreservesGapsAndHandlesBrightnessLimits() {
        String saved = ScreenBrightnessSettings.calibrate("last,eq,7,50", 0, 100);
        assertPercents(ScreenBrightnessSettings.decode(saved), 100, 100);
        assertNull(ScreenBrightnessSettings.matchingRule(ScreenBrightnessSettings.decode(saved), 3));

        saved = ScreenBrightnessSettings.calibrate(saved, 7, 1);
        assertPercents(ScreenBrightnessSettings.decode(saved), 1, 1);
        assertPercents(ScreenBrightnessSettings.decode(
                ScreenBrightnessSettings.calibrate("", 4, 42)), 42);
    }

    @Test
    public void legacyFirstMatchAndNightThresholdSurviveNormalization() {
        String legacy = "night,lt,2,30,night\nfirst,ge,2,10\nsecond,ge,5,90";

        assertPercents(ScreenBrightnessSettings.levels(legacy), 30, 30, 30, 30, 30, 30, 30, 30);
        assertEquals(1, ScreenBrightnessSettings.nightThreshold(legacy));
    }

    @Test
    public void invalidCalibrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScreenBrightnessSettings.calibrate("", -1, 50));
        assertThrows(IllegalArgumentException.class, () -> ScreenBrightnessSettings.calibrate("", 8, 50));
        assertThrows(IllegalArgumentException.class, () -> ScreenBrightnessSettings.calibrate("", 3, 0));
        assertThrows(IllegalArgumentException.class, () -> ScreenBrightnessSettings.calibrate("", 3, 101));
    }

    private static void assertPercents(List<Rule> levels, int... expected) {
        assertEquals(expected.length, levels.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals("Sensor level " + levels.get(index).threshold, expected[index], levels.get(index).percent);
        }
    }
}
