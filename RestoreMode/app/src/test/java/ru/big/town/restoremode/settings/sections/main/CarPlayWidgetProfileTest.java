package ru.big.town.restoremode.settings.sections.main;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Switch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class CarPlayWidgetProfileTest {
    @Test
    public void passengerCarPlayCreationAndRemovalLeaveTheDriverLayoutUntouched() {
        Context context = RuntimeEnvironment.getApplication();
        context.setTheme(R.style.Theme_RestoreMode_Settings);
        DashboardProfile passengerProfile = DashboardProfile.passenger();
        SharedPreferences driver = DashboardProfile.driver().layoutPreferences(context);
        SharedPreferences passenger = passengerProfile.layoutPreferences(context);
        driver.edit().clear().putString("tileOrder", "[]").commit();
        passenger.edit().clear().commit();
        Map<String, ?> driverState = new HashMap<>(driver.getAll());

        View create =
                CarPlayWidgetSettings.create(
                        context,
                        new FrameLayout(context),
                        passenger,
                        passengerProfile,
                        null,
                        () -> true,
                        () -> {});
        ((Switch) create.findViewById(R.id.switchShowCarPlayWidget)).setChecked(true);
        assertEquals(1, AppWidgetStore.load(passenger).size());
        AppWidgetStore.Entry entry = AppWidgetStore.load(passenger).get(0);
        assertTrue(passengerProfile.ownsWidgetId(entry.id));
        assertEquals(driverState, driver.getAll());

        View remove =
                CarPlayWidgetSettings.create(
                        context,
                        new FrameLayout(context),
                        passenger,
                        passengerProfile,
                        entry,
                        () -> true,
                        () -> {});
        ((Switch) remove.findViewById(R.id.switchShowCarPlayWidget)).setChecked(false);
        assertTrue(AppWidgetStore.load(passenger).isEmpty());
        assertEquals(driverState, driver.getAll());
    }
}
