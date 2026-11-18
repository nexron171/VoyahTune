package ru.big.town.restoremode.integration.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.DockIconStyle;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.dialogs.DockIconPicker;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DockIconConfigTest {
    private Application context;
    private SharedPreferences preferences;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("dock-icons-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void publishesIndependentChoicesAndExplicitReset() throws Exception {

        preferences
                .edit()
                .putString("dockOverride1", "same.app")
                .putString("dockOverride2", "same.app")
                .putString("dockOverride1Icon", "navigation")
                .putString("dockOverride2Icon", "music")
                .commit();
        Intent config = publish();
        assertEquals("navigation", config.getStringExtra("dock1Icon"));
        assertEquals("music", config.getStringExtra("dock2Icon"));
        assertEquals(
                "ru.big.town.anative.SetModesConfigReceiver", config.getComponent().getClassName());

        preferences.edit().remove("dockOverride1Icon").commit();
        config = publish();
        assertEquals("default", config.getStringExtra("dock1Icon"));
        assertEquals("music", config.getStringExtra("dock2Icon"));
    }

    @Test
    public void passengerAssignmentsArePublishedIndependently() throws Exception {
        preferences.edit().putString("dockOverride1", "driver.app")
                .putString("passengerDockSlot1", "passenger.app").commit();

        Intent config = publish();
        assertEquals("driver.app", config.getStringExtra("dock1"));
        assertEquals("passenger.app", config.getStringExtra("passengerDockSlot1"));
        assertEquals("none", config.getStringExtra("passengerDockSlot2"));
        preferences.edit().remove("passengerDockSlot1").commit();
        assertEquals("none", publish().getStringExtra("passengerDockSlot1"));
    }

    @Test
    public void unknownChoiceFallsBackToDefault() throws Exception {

        preferences.edit().putString("dockOverride1Icon", "unknown").commit();
        assertEquals("default", publish().getStringExtra("dock1Icon"));
    }

    @Test
    public void navigationButtonOverridesArePublished() throws Exception {
        preferences
                .edit()
                .putBoolean("dockHomeShortVoyahTune", true)
                .putBoolean("dockAllAppsShortVoyahTune", true)
                .commit();

        Intent config = publish();

        assertTrue(config.getBooleanExtra("dockHomeShortVoyahTune", false));
        assertTrue(config.getBooleanExtra("dockAllAppsShortVoyahTune", false));
    }

    @Test
    public void carPlayTargetStaysSeparateFromDashboard() throws Exception {
        preferences
                .edit()
                .putString("dockOverride1", CarPlayTarget.ID)
                .putString("dockOverride2", CarPlayTarget.PACKAGE)
                .commit();

        Intent config = publish();
        assertEquals(CarPlayTarget.ID, config.getStringExtra("dock1"));
        assertEquals(CarPlayTarget.PACKAGE, config.getStringExtra("dock2"));
        assertEquals("default", config.getStringExtra("dock1Icon"));
        assertEquals("default", config.getStringExtra("dock2Icon"));
    }

    @Test
    public void pickerShowsPreviewsAndReturnsEachSelectedStyle() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
            Activity activity = controller.setup().get();
            for (DockIconStyle style : DockIconStyle.driverStyles()) {
                AtomicReference<DockIconStyle> selected = new AtomicReference<>();
                DockIconPicker.show(
                        activity,
                        1,
                        CarPlayTarget.ID,
                        DockIconStyle.driverStyles(),
                        style,
                        selected::set);
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                ListView choices = dialog.getListView();
                assertEquals(3, choices.getCount());
                assertEquals(style.ordinal(), choices.getCheckedItemPosition());
                TextView row =
                        (TextView) choices.getAdapter().getView(style.ordinal(), null, choices);
                assertNotNull(row.getCompoundDrawablesRelative()[0]);
                choices.performItemClick(row, style.ordinal(), style.ordinal());
                assertEquals(style, selected.get());
                assertFalse(dialog.isShowing());
            }
        }
    }

    @Test
    public void passengerPickerOffersOwnArtworkForAPlaceholderAppIcon() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
            Activity activity = controller.setup().get();
            for (DockIconStyle style : DockIconStyle.passengerStyles()) {
                AtomicReference<DockIconStyle> selected = new AtomicReference<>();
                DockIconPicker.show(
                        activity,
                        2,
                        "com.qinggan.app.vehicle",
                        DockIconStyle.passengerStyles(),
                        style,
                        selected::set);
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                ListView choices = dialog.getListView();
                assertEquals(5, choices.getCount());
                int position = DockIconStyle.passengerStyles().indexOf(style);
                assertEquals(position, choices.getCheckedItemPosition());
                TextView row = (TextView) choices.getAdapter().getView(position, null, choices);
                assertNotNull(
                        "Style " + style + " has no preview",
                        row.getCompoundDrawablesRelative()[0]);
                choices.performItemClick(row, position, position);
                assertEquals(style, selected.get());
                assertFalse(dialog.isShowing());
            }
        }
    }

    @Test
    public void passengerIconIsPublishedAndDoesNotMoveTheDriverSlot() throws Exception {

        preferences
                .edit()
                .putString("passengerDockSlot2", "com.qinggan.app.vehicle")
                .putString("passengerDockSlot2Icon", "climate")
                .putString("dockOverride2Icon", "music")
                .commit();
        Intent config = publish();
        assertEquals("climate", config.getStringExtra("passengerDockSlot2Icon"));
        assertEquals("music", config.getStringExtra("dock2Icon"));
        assertEquals("default", config.getStringExtra("passengerDockSlot1Icon"));

        preferences.edit().putString("passengerDockSlot2Icon", "unknown").commit();
        assertEquals("default", publish().getStringExtra("passengerDockSlot2Icon"));
    }

    /** The agent looks artwork up by name in this APK, so a mistyped name loses the style. */
    @Test
    public void everyStyleArtworkIsReachableByNameOnTheDockCanvas() {
        for (String name :
                new String[] {
                    "dock_navigation_icon",
                    "dock_music_icon",
                    "dock_climate_icon",
                    "dock_apps_icon",
                    "carplay_dock_logo"
                }) {
            int id =
                    context.getResources()
                            .getIdentifier(name, "drawable", context.getPackageName());
            assertTrue("Missing drawable " + name, id != 0);
            android.graphics.drawable.Drawable artwork = context.getDrawable(id);
            assertNotNull("Drawable " + name + " did not inflate", artwork);
            assertEquals(name + " must fill the 116x90 canvas", 116, artwork.getIntrinsicWidth());
            assertEquals(name + " must fill the 116x90 canvas", 90, artwork.getIntrinsicHeight());
        }
    }

    private Intent publish() {
        SplitConfigSync.pushDock(context, preferences);
        List<Intent> broadcasts = shadowOf(context).getBroadcastIntents();
        return broadcasts.get(broadcasts.size() - 1);
    }
}
