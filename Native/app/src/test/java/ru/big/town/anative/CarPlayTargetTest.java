package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CarPlayTarget;

public class CarPlayTargetTest {
    @Test
    public void savedCarPlayTargetOpensProjectionOnlyOnVirtualDisplays() {
        for (int physical : new int[] {0, 1}) {
            assertEquals(CarPlayTarget.ACTIVITY,
                    CarPlayTarget.activityForDisplay(CarPlayTarget.ID, physical));
        }
        for (int virtual : new int[] {2, 9, 42}) {
            assertEquals(CarPlayTarget.PROJECTION,
                    CarPlayTarget.activityForDisplay(CarPlayTarget.ID, virtual));
        }
        for (int display : new int[] {0, 1, 9}) {
            assertEquals(CarPlayTarget.PACKAGE + ".MainActivity",
                    CarPlayTarget.activityForDisplay(CarPlayTarget.PACKAGE, display));
            assertNull(CarPlayTarget.activityForDisplay("app.music", display));
        }
    }

    @Test
    public void projectionRequiresTheExactLiveCarPlayVirtualDisplay() {
        assertTrue(CarPlayTarget.canProjectOnDisplay(9, 9, CarPlayTarget.ID));
        assertFalse(CarPlayTarget.canProjectOnDisplay(9, 10, CarPlayTarget.ID));
        assertFalse(CarPlayTarget.canProjectOnDisplay(9, -1, CarPlayTarget.ID));
        assertFalse(CarPlayTarget.canProjectOnDisplay(9, 9, CarPlayTarget.PACKAGE));
        assertFalse(CarPlayTarget.canProjectOnDisplay(9, 9, "app.music"));
        assertFalse(CarPlayTarget.canProjectOnDisplay(9, 9, null));
        for (int physical : new int[] {-1, 0, 1}) {
            assertFalse(CarPlayTarget.canProjectOnDisplay(physical, physical, CarPlayTarget.ID));
        }
    }

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
