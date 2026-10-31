package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DashboardLauncherTest {
    @Test
    public void packageLaunchAlwaysSelectsDriverDashboard() {
        PackageManager manager = RuntimeEnvironment.getApplication().getPackageManager();
        Intent launch = manager.getLaunchIntentForPackage("ru.big.town.restoremode");
        assertNotNull(launch);
        assertTrue(launch.hasCategory(Intent.CATEGORY_INFO));
        assertEquals("ru.big.town.restoremode.MainActivity", launch.getComponent().getClassName());
    }

    @Test
    public void dedicatedLauncherEntriesRemainAvailable() {
        PackageManager manager = RuntimeEnvironment.getApplication().getPackageManager();
        Intent launcher =
                new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage("ru.big.town.restoremode");
        java.util.List<android.content.pm.ResolveInfo> entries =
                manager.queryIntentActivities(launcher, 0);
        assertTrue(
                entries.stream()
                        .anyMatch(
                                entry ->
                                        entry.activityInfo.name.equals(
                                                "ru.big.town.restoremode.carplay.CarPlayLauncherActivity")));
        assertTrue(
                entries.stream()
                        .anyMatch(
                                entry ->
                                        entry.activityInfo.name.equals(
                                                "ru.big.town.restoremode.passenger.PassengerDashboardActivity")));
    }
}
