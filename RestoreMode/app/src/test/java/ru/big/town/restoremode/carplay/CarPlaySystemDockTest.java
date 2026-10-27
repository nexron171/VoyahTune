package ru.big.town.restoremode.carplay;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.common.CarPlaySystemDock;
import ru.big.town.common.CarPlayTarget;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlaySystemDockTest {
    @Test
    public void dockFollowsHostVisibilityAndThemeWhileAirQualityUpdatesFromTheOemReport() {
        try (ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Settings.Global.putInt(activity.getContentResolver(), Settings.Global.BOOT_COUNT, 9);
            CarPlaySystemDock dock = new CarPlaySystemDock(activity);
            activity.setContentView(dock);
            dock.start();
            assertEquals(View.GONE, dock.getVisibility());
            Settings.Global.putInt(
                    activity.getContentResolver(), CarPlayTarget.FULLSCREEN_SETTING, 1);
            Settings.Global.putInt(
                    activity.getContentResolver(), CarPlayTarget.ASTON_MARTIN_SETTING, 1);
            Settings.Global.putString(
                    activity.getContentResolver(), CarPlayTarget.AIR_QUALITY_SETTING, "9:17");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE, dock.getVisibility());
            assertNull(dock.getBackground());
            float density = activity.getResources().getDisplayMetrics().density;
            assertEquals(Math.round(132 * density), dock.containerWidth());
            assertEquals(Math.round(16 * density), dock.getPaddingRight());
            assertEquals(Math.round(16 * density), dock.getPaddingLeft());
            TextView air = (TextView) ((LinearLayout) dock.getChildAt(3)).getChildAt(1);
            assertEquals("17", air.getText().toString());
            dock.stop();
            assertEquals(View.GONE, dock.getVisibility());
            Settings.Global.putString(
                    activity.getContentResolver(), CarPlayTarget.AIR_QUALITY_SETTING, "9:0");
            dock.start();
            assertEquals(View.VISIBLE, dock.getVisibility());
            assertEquals("0", air.getText().toString());
            Settings.Global.putInt(
                    activity.getContentResolver(), CarPlayTarget.ASTON_MARTIN_SETTING, 0);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.GONE, dock.getVisibility());
            dock.stop();
        }
    }

    @Test
    public void unknownMalformedAndPreviousBootSamplesNeverBecomeAZeroMeasurement() {
        for (String sample :
                new String[] {null, "", "3", "3:", "3:7:8", "3:-1", "2:16", "x:1", "3:x"}) {
            assertEquals("—", CarPlaySystemDock.airQualityText(sample, 3));
        }
        assertEquals("—", CarPlaySystemDock.airQualityText("3:1", -1));
        assertEquals("0", CarPlaySystemDock.airQualityText("3:0", 3));
        assertEquals("52", CarPlaySystemDock.airQualityText("3:52", 3));
    }

    @Test
    public void shortcutsOpenTheStockHomeClimateAndVehicleScreensOnTheHostDisplay() {
        try (ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            CarPlaySystemDock dock = new CarPlaySystemDock(activity);
            activity.setContentView(dock);
            String[] actions = {
                Intent.ACTION_MAIN,
                "com.qinggan.app.vehicle.SHOW_AIR",
                "com.qinggan.app.vehiclesetting.START_VEHICLE"
            };
            for (int index = 0; index < actions.length; index++) {
                dock.getChildAt(index).performClick();
                org.robolectric.shadows.ShadowActivity.IntentForResult launched =
                        shadowOf(activity).getNextStartedActivityForResult();
                assertEquals(actions[index], launched.intent.getAction());
                assertEquals(0, launched.options.getInt("android.activity.launchDisplayId", -1));
                if (index == 0) {
                    assertEquals(
                            "com.qinggan.app.launcher.activity.MainActivity",
                            launched.intent.getComponent().getClassName());
                    assertFalse(launched.intent.getBooleanExtra("showAllApp", true));
                }
            }
        }
    }
}
