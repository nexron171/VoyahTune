package ru.big.town.restoremode.settings.ui.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.energy.CarAppearance;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsColorPaletteTest {
    private SharedPreferences preferences;

    @Before
    public void clearPreferences() {
        preferences =
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void everyColorPersistsAndRestoresWhenSettingsReopen() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().visible().get();
            SettingsColorPalette palette = showPalette(activity);
            for (int colorIndex = 0; colorIndex < CarAppearance.COLORS.length; colorIndex++) {
                assertTrue(swatch(palette, colorIndex).performClick());
                assertEquals(
                        CarAppearance.COLORS[colorIndex],
                        preferences.getString("energyCarColor", null));
                assertSelectedColor(palette, colorIndex);

                palette = showPalette(activity);
                assertSelectedColor(palette, colorIndex);
            }
        }
    }

    @Test
    public void reusedRowReadsSavedColorWhenReattached() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().visible().get();
            SettingsColorPalette palette = showPalette(activity);
            View row = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            activity.setContentView(new FrameLayout(activity));
            assertFalse(palette.isAttachedToWindow());

            preferences.edit().putString("energyCarColor", "white").commit();
            activity.setContentView(row);
            assertTrue(palette.isAttachedToWindow());
            assertSelectedColor(palette, 1);
        }
    }

    @Test
    public void missingOrUnknownColorDisplaysBurgundyWithoutOverwritingSettings() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().visible().get();
            assertSelectedColor(showPalette(activity), 4);
            assertNull(preferences.getString("energyCarColor", null));

            preferences.edit().putString("energyCarColor", "unknown").commit();
            assertSelectedColor(showPalette(activity), 4);
            assertEquals("unknown", preferences.getString("energyCarColor", null));
        }
    }

    private SettingsColorPalette showPalette(Activity activity) {
        View row =
                LayoutInflater.from(activity)
                        .inflate(R.layout.settings_main_vehicle_parameters, null, false);
        activity.setContentView(row);
        return row.findViewById(R.id.energyCarColor);
    }

    private View swatch(SettingsColorPalette palette, int colorIndex) {
        ArrayList<View> matches = new ArrayList<>();
        palette.findViewsWithText(
                matches,
                CarAppearance.COLOR_NAMES[colorIndex],
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private void assertSelectedColor(SettingsColorPalette palette, int selectedColor) {
        for (int colorIndex = 0; colorIndex < CarAppearance.COLORS.length; colorIndex++) {
            assertEquals(colorIndex == selectedColor, swatch(palette, colorIndex).isSelected());
        }
        ArrayList<View> labels = new ArrayList<>();
        palette.findViewsWithText(
                labels, CarAppearance.COLOR_NAMES[selectedColor], View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, labels.size());
    }
}
