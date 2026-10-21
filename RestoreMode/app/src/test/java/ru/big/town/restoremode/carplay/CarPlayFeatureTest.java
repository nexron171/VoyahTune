package ru.big.town.restoremode.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Looper;

import com.shilapi.xcertplay.CarPlaySettingsAccess;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.DashboardTarget;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;
import ru.big.town.restoremode.widgets.apps.AppWidgetSessionStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlayFeatureTest {
    private final Application application = RuntimeEnvironment.getApplication();

    @Test
    public void defaultEnabledAndLauncherRoutingStayIndependentAcrossToggle() throws Exception {
        assertTrue(CarPlayFeature.isEnabled(application));
        for (boolean enabled : new boolean[] {false, true}) {
            CarPlayFeature.setEnabled(application, enabled);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(enabled, CarPlayTarget.isEnabled(application));
            PackageManager manager = application.getPackageManager();
            assertEquals(enabled ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    manager.getComponentEnabledSetting(
                            new ComponentName(application, CarPlayTarget.PROJECTION)));
            assertTrue(manager.getActivityInfo(
                    new ComponentName(application, DashboardTarget.DRIVER), 0).enabled);
            for (int display : new int[] {0, 1}) {
                Intent dashboard = CarPlayTarget.launchIntent(application, CarPlayTarget.PACKAGE, display);
                assertEquals(DashboardTarget.activityForDisplay(display),
                        dashboard.getComponent().getClassName());
                Intent carPlay = CarPlayTarget.launchIntent(application, CarPlayTarget.ID, display);
                if (enabled) {
                    assertEquals(CarPlayTarget.ENTRY, carPlay.getComponent().getClassName());
                } else {
                    assertNull(carPlay);
                }
            }
        }
    }

    @Test
    public void disablingRemovesBothScreenWidgetsAndDockButPreservesOtherProfiles() {
        SharedPreferences settings = DashboardProfile.driver().settingsPreferences(application);
        settings.edit().putString("dockOverride1", CarPlayTarget.ID)
                .putString("dockOverride1Label", "CarPlay")
                .putString("dockOverride2", "app.music")
                .putBoolean(VoiceSteeringPolicy.SIRI_ENABLED, true).commit();
        for (DashboardProfile profile : new DashboardProfile[] {
                DashboardProfile.driver(), DashboardProfile.passenger()}) {
            SharedPreferences layout = profile.layoutPreferences(application);
            AppWidgetStore.add(layout, CarPlayTarget.ID, profile.widgetIdPrefix());
            AppWidgetStore.Entry mixed = AppWidgetStore.add(layout, "app.music", profile.widgetIdPrefix());
            AppWidgetStore.addProfile(mixed, CarPlayTarget.ID, 240);
            mixed.selectedProfile = 1;
            mixed.autoStart = true;
            AppWidgetStore.update(layout, mixed);
            AppWidgetSessionStore.saveLaunch(layout, AppWidgetSessionStore.sessionId(application, layout),
                    mixed.id, CarPlayTarget.ID, 240);
        }

        CarPlayFeature.setEnabled(application, false);
        shadowOf(Looper.getMainLooper()).idle();

        for (DashboardProfile profile : new DashboardProfile[] {
                DashboardProfile.driver(), DashboardProfile.passenger()}) {
            java.util.List<AppWidgetStore.Entry> entries =
                    AppWidgetStore.load(profile.layoutPreferences(application));
            assertEquals(1, entries.size());
            assertEquals("app.music", entries.get(0).packageName);
            assertEquals(1, entries.get(0).profiles.size());
            assertFalse(entries.get(0).autoStart);
        }
        assertEquals("", settings.getString("dockOverride1", ""));
        assertEquals("", settings.getString("dockOverride1Label", ""));
        assertEquals("app.music", settings.getString("dockOverride2", ""));
        assertFalse(settings.getBoolean(VoiceSteeringPolicy.SIRI_ENABLED, true));
        Intent dock = shadowOf(application).getBroadcastIntents().stream()
                .filter(intent -> "ru.big.town.anative.DOCK_CONFIG".equals(intent.getAction()))
                .reduce((first, second) -> second).get();
        assertFalse(dock.getBooleanExtra(CarPlayTarget.ENABLED, true));
        assertEquals("none", dock.getStringExtra("dock1"));

        CarPlayFeature.setEnabled(application, true);
        assertEquals(1, AppWidgetStore.load(settings).size());
        assertEquals("", settings.getString("dockOverride1", ""));
    }
}
