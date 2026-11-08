package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemProperties;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w1920dp-h720dp-land-mdpi", application = Application.class)
public class DashboardScreenLiftTest {
    private static final String PROPERTY = "persist.qg.canbus.bcm_screenAutoLiftFdb";

    @Test
    public void reducedAppMetricsDoNotShrinkFullscreenViewportAfterLiftCycle() {
        Context context = RuntimeEnvironment.getApplication();
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int originalHeight = metrics.heightPixels;
        metrics.heightPixels = 640;
        ShadowSystemProperties.override(PROPERTY, "2");
        View root = root(context);
        root.layout(0, 0, 1920, 720);
        try (DashboardScreenLift lift = new DashboardScreenLift(context, root, () -> {})) {
            assertEquals(720, lift.viewportHeight());
            ShadowSystemProperties.override(PROPERTY, "1");
            send(context, 1);
            assertEquals(560, lift.viewportHeight());
            assertEquals(560, root.getLayoutParams().height);
            root.layout(0, 0, 1920, 560);
            ShadowSystemProperties.override(PROPERTY, "2");
            send(context, 2);
            assertEquals(720, lift.viewportHeight());
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, root.getLayoutParams().height);
        } finally {
            metrics.heightPixels = originalHeight;
        }
    }

    @Test
    public void canPropertyWinsOverStaleSavedState() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), "voyahtune_screen_lift_type", 2);
        ShadowSystemProperties.override(PROPERTY, "1");
        View root = root(context);
        try (DashboardScreenLift lift = new DashboardScreenLift(context, root, () -> {})) {
            assertTrue(lift.isLowered());
            assertEquals(560, lift.viewportHeight());
            assertEquals(560, root.getLayoutParams().height);
        }
    }

    @Test
    public void staleAndInvalidEventsAreIgnoredAndValidEventsRestoreFullHeight() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSystemProperties.override(PROPERTY, "2");
        View root = root(context);
        int[] updates = {0};
        try (DashboardScreenLift lift =
                new DashboardScreenLift(context, root, () -> updates[0]++)) {
            send(context, 1);
            send(context, 9);
            assertFalse(lift.isLowered());
            assertEquals(0, updates[0]);
            ShadowSystemProperties.override(PROPERTY, "1");
            send(context, 1);
            assertTrue(lift.isLowered());
            assertEquals(1, updates[0]);
            send(context, 1);
            assertEquals(1, updates[0]);
            ShadowSystemProperties.override(PROPERTY, "2");
            send(context, 2);
            assertFalse(lift.isLowered());
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, root.getLayoutParams().height);
            assertEquals(720, lift.viewportHeight());
            assertEquals(2, updates[0]);
        }
        send(context, 1);
        assertEquals(2, updates[0]);
    }

    private static View root(Context context) {
        View root = new View(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        return root;
    }

    private static void send(Context context, int type) {
        context.sendBroadcast(new Intent("action.qg.layout.changed").putExtra("type", type));
        shadowOf(Looper.getMainLooper()).idle();
    }
}
