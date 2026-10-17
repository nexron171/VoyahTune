package ru.big.town.restoremode.widgets.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AppWidgetSessionStoreTest {
    private static final String FIRST_SESSION = "12:3";
    private static final String NEXT_SESSION = "12:4";
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        preferences =
                context.getSharedPreferences(
                        "app_widget_session_store_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void automaticLaunchIsHandledOnlyOncePerSession() {
        assertTrue(AppWidgetSessionStore.isFirstOpening(preferences, FIRST_SESSION));

        AppWidgetSessionStore.markOpeningHandled(preferences, FIRST_SESSION);

        assertFalse(AppWidgetSessionStore.isFirstOpening(preferences, FIRST_SESSION));
        assertTrue(AppWidgetSessionStore.isFirstOpening(preferences, NEXT_SESSION));
    }

    @Test
    public void manualLaunchSurvivesReopenUntilExplicitlyForgotten() {
        AppWidgetSessionStore.saveLaunch(
                preferences, FIRST_SESSION, "tile-a", "app.music", 240);

        AppWidgetSessionStore.Launch restored =
                AppWidgetSessionStore.findLaunch(preferences, FIRST_SESSION, "tile-a");

        assertEquals("app.music", restored.packageName);
        assertEquals(240, restored.dpi);

        AppWidgetSessionStore.forgetLaunch(preferences, FIRST_SESSION, "tile-a");

        assertNull(AppWidgetSessionStore.findLaunch(preferences, FIRST_SESSION, "tile-a"));
    }

    @Test
    public void changingSessionDropsPreviousLaunchMemory() {
        AppWidgetSessionStore.saveLaunch(
                preferences, FIRST_SESSION, "tile-a", "app.music", 160);

        assertNull(AppWidgetSessionStore.findLaunch(preferences, NEXT_SESSION, "tile-a"));
    }

    @Test
    public void unsupportedDpiIsNormalizedBeforeRestoring() {
        AppWidgetSessionStore.saveLaunch(
                preferences, FIRST_SESSION, "tile-a", "app.music", 9999);

        AppWidgetSessionStore.Launch restored =
                AppWidgetSessionStore.findLaunch(preferences, FIRST_SESSION, "tile-a");

        assertEquals(AppWidgetStore.normalizeDpi(9999), restored.dpi);
    }
}
