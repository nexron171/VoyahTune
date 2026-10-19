package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.common.DashboardTarget;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DashboardTaskIsolationTest {
    private Context context;
    private SharedPreferences driver;
    private SharedPreferences passenger;

    @Before
    public void clearLayouts() {
        context = RuntimeEnvironment.getApplication();
        driver = DashboardProfile.driver().layoutPreferences(context);
        passenger = DashboardProfile.passenger().layoutPreferences(context);
        driver.edit().clear().commit();
        passenger.edit().clear().commit();
    }

    @Test
    public void physicalLaunchesResolveToIndependentReusableTasks() throws Exception {
        Intent driverLaunch = DashboardTarget.intent(0);
        Intent passengerLaunch = DashboardTarget.intent(1);
        ActivityInfo driverInfo =
                context.getPackageManager().getActivityInfo(driverLaunch.getComponent(), 0);
        ActivityInfo passengerInfo =
                context.getPackageManager().getActivityInfo(passengerLaunch.getComponent(), 0);

        assertNotEquals(driverInfo.name, passengerInfo.name);
        assertNotEquals(driverInfo.taskAffinity, passengerInfo.taskAffinity);
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, driverInfo.launchMode);
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, passengerInfo.launchMode);
        assertTrue(passengerInfo.exported);
        for (int displayId : new int[] {0, 1}) {
            Intent launch = DashboardTarget.intent(displayId);
            assertEquals(launch.getComponent(), DashboardTarget.intent(displayId).getComponent());
            ActivityOptions options =
                    ReflectionHelpers.callStaticMethod(
                            ActivityOptions.class,
                            "fromBundle",
                            ReflectionHelpers.ClassParameter.from(
                                    Bundle.class, DashboardTarget.options(displayId)));
            assertEquals(displayId, options.getLaunchDisplayId());
            assertTrue((launch.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        }
    }

    @Test
    public void passengerSettingsAppearInNewAndSavedLayoutsWithoutChangingDriver() {
        TileOrderStore.save(
                driver,
                Collections.singletonList(
                        new TileOrderStore.Tile(TileOrderStore.Tile.TYPE_WIDGET, "tripCard")));
        driver.edit().putBoolean("showTripTimer", true).commit();
        String driverOrder = driver.getString("tileOrder", "");

        for (boolean existingLayout : new boolean[] {false, true}) {
            passenger.edit().clear().commit();
            if (existingLayout) {
                TileOrderStore.save(
                        passenger,
                        Collections.singletonList(
                                new TileOrderStore.Tile(TileOrderStore.Tile.TYPE_WIDGET, "tripCard")));
            }
            TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);
            TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);

            long settingsCount =
                    TileOrderStore.load(passenger).stream()
                            .filter(tile -> "cardSettings".equals(tile.id))
                            .count();
            assertEquals(1, settingsCount);
            assertEquals(driverOrder, driver.getString("tileOrder", ""));
            assertTrue(driver.getBoolean("showTripTimer", false));
        }
    }
}
