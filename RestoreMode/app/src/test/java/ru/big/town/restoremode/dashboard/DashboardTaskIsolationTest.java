package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.common.DashboardTarget;
import ru.big.town.common.ScenarioProtocol;
import ru.big.town.restoremode.AdvanceActivity;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
                                new TileOrderStore.Tile(
                                        TileOrderStore.Tile.TYPE_WIDGET, "tripCard")));
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

    @Test
    public void passengerKeepsTheCompleteWidgetCatalogAndItsOwnSavedPositions() {
        TileOrderStore.sync(driver, driver, context.getPackageManager(), false);
        Set<String> driverWidgets = widgetIds(TileOrderStore.load(driver));
        String driverOrder = driver.getString("tileOrder", "");

        for (boolean existingLayout : new boolean[] {false, true}) {
            passenger.edit().clear().commit();
            if (existingLayout) {
                TileOrderStore.save(
                        passenger,
                        Collections.singletonList(
                                new TileOrderStore.Tile(
                                        TileOrderStore.Tile.TYPE_WIDGET, "odometerWidget")));
            }
            passenger.edit().putBoolean("showSuspensionWidget", true).commit();
            TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);
            TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);

            List<TileOrderStore.Tile> passengerTiles = TileOrderStore.load(passenger);
            assertEquals(driverWidgets, widgetIds(passengerTiles));
            assertEquals(
                    driverWidgets.size(),
                    passengerTiles.stream()
                            .filter(tile -> TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type))
                            .count());
            if (existingLayout) {
                assertEquals("odometerWidget", passengerTiles.get(0).id);
            }
            assertEquals(driverOrder, driver.getString("tileOrder", ""));
            assertFalse(driver.getBoolean("showSuspensionWidget", false));
        }
    }

    @Test
    public void passengerScenarioTilesReadTheSharedDefinitions() {
        ScenarioStore.Scenario scenario = new ScenarioStore.Scenario();
        scenario.showTile = true;
        driver.edit()
                .putString(
                        ScenarioProtocol.KEY_SCENARIOS,
                        ScenarioStore.toJson(Collections.singletonList(scenario)))
                .commit();

        TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);
        TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);
        assertEquals(
                1L,
                TileOrderStore.load(passenger).stream()
                        .filter(
                                tile ->
                                        TileOrderStore.Tile.TYPE_SCENARIO.equals(tile.type)
                                                && scenario.id.equals(tile.id))
                        .count());

        scenario.enabled = false;
        driver.edit()
                .putString(
                        ScenarioProtocol.KEY_SCENARIOS,
                        ScenarioStore.toJson(Collections.singletonList(scenario)))
                .commit();
        TileOrderStore.sync(passenger, driver, context.getPackageManager(), true);
        assertFalse(
                TileOrderStore.load(passenger).stream()
                        .anyMatch(tile -> scenario.id.equals(tile.id)));
    }

    @Test
    public void settingsShortcutOpensTheSectionForItsDashboardProfile() {
        DashboardActivity activity = Robolectric.buildActivity(DashboardActivity.class).get();
        for (DashboardProfile profile :
                new DashboardProfile[] {DashboardProfile.driver(), DashboardProfile.passenger()}) {
            ReflectionHelpers.setField(activity, "dashboardProfile", profile);
            activity.initIntent();
            Intent settings = ReflectionHelpers.getField(activity, "resultIntent");
            assertEquals(
                    profile.isPassenger()
                            ? SettingsSection.PASSENGER.identifier
                            : SettingsSection.MAIN.identifier,
                    settings.getIntExtra(AdvanceActivity.EXTRA_SECTION, -1));
        }
    }

    private static Set<String> widgetIds(List<TileOrderStore.Tile> tiles) {
        return tiles.stream()
                .filter(tile -> TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type))
                .map(tile -> tile.id)
                .collect(Collectors.toSet());
    }
}
