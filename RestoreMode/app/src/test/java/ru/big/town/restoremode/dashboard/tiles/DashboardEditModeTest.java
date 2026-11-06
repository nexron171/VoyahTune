package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.restoremode.MainActivity;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.energy.VehicleWidgets;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w1920dp-h720dp-land-mdpi", application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DashboardEditModeTest {
    @Test
    public void editTripCancelResizeCommitResizeAndRemoveEntireAppTile() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("DrivePreferences", 0);
        prefs.edit().putBoolean("show_energyTripWidget", true).apply();
        AppWidgetStore.Entry app = AppWidgetStore.add(prefs, context.getPackageName(), "");
        app.width = 4;
        AppWidgetStore.addProfile(app, "another.profile", 200);
        AppWidgetStore.update(prefs, app);
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new android.content.ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.MyService"),
                        new android.os.Messenger(new android.os.Handler(Looper.getMainLooper()))
                                .getBinder());
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        try {
            DashboardEditGrid grid = controller.get().findViewById(R.id.splitTilesGrid);
            shadowOf(Looper.getMainLooper()).idle();
            layout(grid);
            View trip = tile(grid, "widget:" + VehicleWidgets.TRIP);
            View history = trip.findViewById(R.id.buttonCurrentTripHistory);
            boolean[] held = {false};
            history.setOnLongClickListener(
                    view -> {
                        held[0] = true;
                        return true;
                    });
            float historyX = trip.getX() + history.getX() + history.getWidth() / 2f;
            float historyY = trip.getY() + history.getY() + history.getHeight() / 2f;
            touch(grid, MotionEvent.ACTION_DOWN, historyX, historyY);
            shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
            touch(grid, MotionEvent.ACTION_UP, historyX, historyY);
            assertTrue("Nested button should receive its own long press", held[0]);
            assertFalse(grid.isEditing());
            touch(grid, MotionEvent.ACTION_DOWN, trip.getLeft() + 50, trip.getTop() + 50);
            shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
            assertTrue(grid.isEditing());
            touch(grid, MotionEvent.ACTION_UP, trip.getLeft() + 50, trip.getTop() + 50);
            trip = tile(grid, "widget:" + VehicleWidgets.TRIP);
            float x = trip.getRight() - 8;
            float y = trip.getBottom() - 8;
            float step = (trip.getWidth() + 8) / 2f;
            touch(grid, MotionEvent.ACTION_DOWN, x, y);
            int initialWidth = trip.getWidth();
            int dragDistance = Math.round(step / 4);
            touch(grid, MotionEvent.ACTION_MOVE, x + dragDistance, y);
            assertEquals(initialWidth + dragDistance, trip.getWidth());
            touch(grid, MotionEvent.ACTION_MOVE, x + step, y);
            int heldWidth = trip.getWidth();
            grid.requestLayout();
            layout(grid);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
            assertEquals(heldWidth, trip.getWidth());
            touch(grid, MotionEvent.ACTION_MOVE, x + step, y);
            assertEquals(
                    "A layout pass while holding must keep the preview",
                    heldWidth,
                    trip.getWidth());
            assertTrue(trip.getWidth() > 2 * step);
            assertArrayEquals(
                    new int[] {2, 3}, TileSizeStore.dimensions(prefs, VehicleWidgets.TRIP, 2, 3));
            touch(grid, MotionEvent.ACTION_CANCEL, x + step, y);
            assertTrue(trip.getWidth() < 2 * step);
            touch(grid, MotionEvent.ACTION_DOWN, x, y);
            touch(grid, MotionEvent.ACTION_UP, x + step, y);
            assertArrayEquals(
                    new int[] {3, 3}, TileSizeStore.dimensions(prefs, VehicleWidgets.TRIP, 2, 3));

            layout(grid);
            trip = tile(grid, "widget:" + VehicleWidgets.TRIP);
            x = trip.getRight() - 8;
            y = trip.getBottom() - 8;
            touch(grid, MotionEvent.ACTION_DOWN, x, y);
            touch(grid, MotionEvent.ACTION_UP, x - step, y);
            assertArrayEquals(
                    new int[] {2, 3}, TileSizeStore.dimensions(prefs, VehicleWidgets.TRIP, 2, 3));
            layout(grid);
            View settings = tile(grid, "widget:cardSettings");
            touch(grid, MotionEvent.ACTION_DOWN, settings.getLeft() + 16, settings.getTop() + 16);
            touch(grid, MotionEvent.ACTION_UP, settings.getLeft() + 16, settings.getTop() + 16);
            assertNotNull(tileOrNull(grid, "widget:cardSettings"));
            View appTile = tile(grid, "appWidget:" + app.id);
            controller.get().findViewById(R.id.dashboardEditDone).performClick();
            ViewGroup profiles = appTile.findViewById(R.id.appWidgetList);
            profiles.getChildAt(0).performClick();
            layout(grid);
            float windowX = appTile.getLeft() + appTile.getWidth() / 2f;
            float windowY = appTile.getTop() + appTile.getHeight() / 2f;
            touch(grid, MotionEvent.ACTION_DOWN, windowX, windowY);
            shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
            touch(grid, MotionEvent.ACTION_UP, windowX, windowY);
            assertTrue(grid.isEditing());
            assertEquals(
                    View.VISIBLE,
                    appTile.findViewById(R.id.appWidgetOverlayControls).getVisibility());
            layout(grid);
            View close = appTile.findViewById(R.id.appWidgetClose);
            android.graphics.Rect closeBounds =
                    new android.graphics.Rect(0, 0, close.getWidth(), close.getHeight());
            grid.offsetDescendantRectToMyCoords(close, closeBounds);
            touch(grid, MotionEvent.ACTION_DOWN, closeBounds.centerX(), closeBounds.centerY());
            touch(grid, MotionEvent.ACTION_UP, closeBounds.centerX(), closeBounds.centerY());
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(
                    View.GONE, appTile.findViewById(R.id.appWidgetOverlayControls).getVisibility());
            assertNotNull(AppWidgetStore.find(prefs, app.id));
            touch(grid, MotionEvent.ACTION_DOWN, appTile.getLeft() + 16, appTile.getTop() + 16);
            touch(grid, MotionEvent.ACTION_UP, appTile.getLeft() + 16, appTile.getTop() + 16);
            assertNull(AppWidgetStore.find(prefs, app.id));
            assertNull(tileOrNull(grid, "appWidget:" + app.id));
            assertTrue(grid.isEditing());
            Button done = controller.get().findViewById(R.id.dashboardEditDone);
            assertNotNull(done);
            done.performClick();
            assertFalse(grid.isEditing());
            assertNull(controller.get().findViewById(R.id.dashboardEditDone));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void layout(DashboardEditGrid grid) {
        grid.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(680, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, grid.getMeasuredWidth(), grid.getMeasuredHeight());
    }

    private static View tileOrNull(ViewGroup grid, String name) {
        for (int index = 0; index < grid.getChildCount(); index++) {
            View view = grid.getChildAt(index);
            if (name.equals(view.getTransitionName())) return view;
        }
        return null;
    }

    private static View tile(ViewGroup grid, String name) {
        View view = tileOrNull(grid, name);
        assertNotNull(name, view);
        return view;
    }

    private static void touch(View view, int action, float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}
