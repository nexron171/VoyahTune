package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import ru.big.town.restoremode.widgets.apps.AppWidgetSessionStore;

@RunWith(RobolectricTestRunner.class)
public class EmbeddedTaskClosedReceiverTest {
    @Test
    public void handoffForgetsSourceWhileDashboardIsClosedButKeepsDestination() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences driver = preferences(context, DashboardProfile.SETTINGS_PREFERENCES);
        SharedPreferences passenger = preferences(context, DashboardProfile.PASSENGER_PREFERENCES);
        remember(context, driver, "source");
        remember(context, passenger, "passenger-target");

        new EmbeddedTaskClosedReceiver()
                .onReceive(context, closeIntent().putExtra("widgetId", "source"));

        assertNull(
                AppWidgetSessionStore.findLaunch(
                        driver, AppWidgetSessionStore.sessionId(context, driver), "source"));
        assertNotNull(
                AppWidgetSessionStore.findLaunch(
                        passenger,
                        AppWidgetSessionStore.sessionId(context, passenger),
                        "passenger-target"));
    }

    @Test
    public void packageCloseStillForgetsBothScreens() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences driver = preferences(context, DashboardProfile.SETTINGS_PREFERENCES);
        SharedPreferences passenger = preferences(context, DashboardProfile.PASSENGER_PREFERENCES);
        remember(context, driver, "source");
        remember(context, passenger, "passenger-target");

        new EmbeddedTaskClosedReceiver().onReceive(context, closeIntent());

        assertNull(
                AppWidgetSessionStore.findLaunch(
                        driver, AppWidgetSessionStore.sessionId(context, driver), "source"));
        assertNull(
                AppWidgetSessionStore.findLaunch(
                        passenger,
                        AppWidgetSessionStore.sessionId(context, passenger),
                        "passenger-target"));
    }

    private SharedPreferences preferences(Context context, String name) {
        SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        return preferences;
    }

    private void remember(Context context, SharedPreferences preferences, String widgetId) {
        AppWidgetSessionStore.saveLaunch(
                preferences,
                AppWidgetSessionStore.sessionId(context, preferences),
                widgetId,
                "app.music",
                213);
    }

    private Intent closeIntent() {
        return new Intent(DashboardActivity.ACTION_EMBEDDED_TASK_LEFT)
                .putExtra(DashboardActivity.EXTRA_EMBEDDED_TASK_PKG, "app.music")
                .putExtra(DashboardActivity.EXTRA_EMBEDDED_TASK_CLOSED, true);
    }
}
