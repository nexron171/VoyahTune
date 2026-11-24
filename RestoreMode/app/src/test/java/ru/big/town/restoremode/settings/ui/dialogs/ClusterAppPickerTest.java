package ru.big.town.restoremode.settings.ui.dialogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.widget.FrameLayout;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPackageManager;

import ru.big.town.common.VehiclePlatform;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ClusterAppPickerTest {
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private SharedPreferences preferences;
    private FrameLayout anchor;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup();
        anchor = new FrameLayout(activity);
        activity.setContentView(anchor);
        preferences = activity.getSharedPreferences("steering", 0);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test
    public void shortAndLongPressesKeepTheirOwnAppAndPlacement() {
        registerLauncher("com.example.maps", "Карты");
        registerLauncher("com.example.music", "Музыка");
        registerLauncher("com.qinggan.instrumentcard", "Штатная карточка");

        choose("steerStarShort", "com.example.maps", "В левой медиакарточке");
        choose("steerStarLong", "com.example.music", "В центре приборной панели");
        assertEquals(
                List.of("cluster_app:media:com.example.maps"),
                SteeringActionStore.load(preferences, "steerStarShort"));
        assertEquals(
                List.of("cluster_app:center:com.example.music"),
                SteeringActionStore.load(preferences, "steerStarLong"));
        assertTrue(
                SettingsActionPicker.label(
                                activity, preferences, "cluster_app:center:com.example.music")
                        .contains("центр приборной панели"));
    }

    @Test
    public void mediaOnlyPlatformsAcceptImmediatelyWithoutShowingALocationDialog() {
        for (VehiclePlatform model : List.of(VehiclePlatform.H97X, VehiclePlatform.H97Y)) {
            android.app.Dialog previous = ShadowDialog.getLatestDialog();
            List<String> result = new ArrayList<>();
            SettingsActionPicker.pickClusterPlacement(
                    activity, model, "com.example.maps", result::add);
            assertEquals(List.of("cluster_app:media:com.example.maps"), result);
            assertSame(previous, ShadowDialog.getLatestDialog());
        }
    }

    @Test
    public void cancellingTheCardChoiceDoesNotCommitAnAssignment() {
        List<String> result = new ArrayList<>();
        SettingsActionPicker.pickClusterPlacement(
                activity, VehiclePlatform.H97C, "com.example.maps", result::add);
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertTrue(result.isEmpty());
    }

    private void choose(String slot, String packageName, String placement) {
        SettingsActionPicker.show(
                activity,
                preferences,
                anchor,
                action -> SteeringActionStore.save(preferences, slot, List.of(action)));
        clickItem("Открыть приложение на приборной панели…");
        ListView apps = latestDialog().getListView();
        for (int i = 0; i < apps.getCount(); i++) {
            assertFalse(
                    apps.getItemAtPosition(i).toString().contains("com.qinggan.instrumentcard"));
        }
        clickItem(packageName);
        clickItem(placement);
    }

    private void registerLauncher(String packageName, String label) {
        ComponentName component = ComponentName.createRelative(packageName, ".MainActivity");
        ShadowPackageManager manager = shadowOf(activity.getPackageManager());
        manager.addActivityIfNotPresent(component).nonLocalizedLabel = label;
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_LAUNCHER);
        manager.addIntentFilterForActivity(component, filter);
    }

    private static AlertDialog latestDialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void clickItem(String text) {
        ListView list = latestDialog().getListView();
        for (int i = 0; i < list.getCount(); i++) {
            if (list.getItemAtPosition(i).toString().contains(text)) {
                list.performItemClick(null, i, i);
                return;
            }
        }
        throw new AssertionError("Missing dialog item: " + text);
    }
}
