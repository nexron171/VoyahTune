package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CarPlayTarget;

public class CarPlayTargetTest {
    @Test
    public void transferSelectsCarPlayTasksWithoutRetiringVoyahTuneScreens() {
        assertTrue(
                CarPlayTarget.matches(
                        CarPlayTarget.ID,
                        CarPlayTarget.PACKAGE,
                        "com.shilapi.xcertplay.DiPlayActivity"));
        assertTrue(
                CarPlayTarget.matches(
                        CarPlayTarget.ID,
                        CarPlayTarget.PACKAGE,
                        "com.shilapi.xcertplay.CarPlayHostActivity"));
        assertFalse(
                CarPlayTarget.matches(
                        CarPlayTarget.ID,
                        CarPlayTarget.PACKAGE,
                        "ru.big.town.restoremode.dashboard.DashboardActivity"));
        assertFalse(
                CarPlayTarget.matches(
                        CarPlayTarget.ID,
                        CarPlayTarget.PACKAGE,
                        "ru.big.town.restoremode.SettingsActivity"));
        assertFalse(
                CarPlayTarget.matches(
                        CarPlayTarget.ID,
                        "org.voyahtune.diplay.odtest",
                        "com.shilapi.xcertplay.CarPlayHostActivity"));
    }

    @Test
    public void onlyTheCompactCarPlayTileOpensAnActivity() {
        assertTrue(CarPlayTarget.opensActivity(CarPlayTarget.ID, 1, 1));
        for (int width = 8; width <= 12; width++) {
            assertFalse(CarPlayTarget.opensActivity(CarPlayTarget.ID, width, 5));
        }
        assertFalse(CarPlayTarget.opensActivity("app.music", 1, 1));
    }

    @Test
    public void ordinaryAppsStillMatchByPackage() {
        assertTrue(CarPlayTarget.matches("app.music", "app.music", "app.music.Main"));
        assertFalse(CarPlayTarget.matches("app.music", "app.other", "app.music.Main"));
        assertFalse(CarPlayTarget.matches(null, "app.music", "app.music.Main"));
    }
}
