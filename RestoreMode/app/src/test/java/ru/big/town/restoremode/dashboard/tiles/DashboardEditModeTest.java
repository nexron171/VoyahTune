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
        bindNativeService();
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
            touch(grid, MotionEvent.ACTION_MOVE, x + step * 0.91f, y);
            assertEquals("Snap when approaching the next column", heldWidth, trip.getWidth());
            touch(grid, MotionEvent.ACTION_MOVE, x + step * 1.08f, y);
            assertEquals("Keep the same column just beyond its edge", heldWidth, trip.getWidth());
            float rowStep = (trip.getHeight() + 8) / 3f;
            touch(grid, MotionEvent.ACTION_MOVE, x + step, y + rowStep);
            int snappedHeight = trip.getHeight();
            touch(grid, MotionEvent.ACTION_MOVE, x + step * 0.94f, y + rowStep * 0.92f);
            assertEquals(heldWidth, trip.getWidth());
            assertEquals(snappedHeight, trip.getHeight());
            touch(grid, MotionEvent.ACTION_MOVE, x + step * 0.92f, y);
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
            // The trip widget only supports two rows at wider spans.
            int initialHeight = trip.getHeight();
            int towardsTwoRows = Math.round(rowStep * 0.92f);
            touch(grid, MotionEvent.ACTION_DOWN, x, y);
            touch(grid, MotionEvent.ACTION_MOVE, x, y - towardsTwoRows);
            assertEquals(initialHeight - towardsTwoRows, trip.getHeight());
            touch(grid, MotionEvent.ACTION_UP, x, y - towardsTwoRows);
            assertEquals(initialHeight, trip.getHeight());
            assertArrayEquals(
                    new int[] {2, 3}, TileSizeStore.dimensions(prefs, VehicleWidgets.TRIP, 2, 3));
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
            assertControlReceivesTap(grid, appTile.findViewById(R.id.appWidgetSwapLauncher));
            assertControlReceivesHold(grid, appTile.findViewById(R.id.appWidgetDragHandleLauncher));
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
            assertControlReceivesHold(grid, appTile.findViewById(R.id.appWidgetDragHandle));
            assertControlReceivesTap(grid, appTile.findViewById(R.id.appWidgetSwap));
            assertControlReceivesTap(grid, appTile.findViewById(R.id.appWidgetExpand));
            int originalDpi = AppWidgetStore.find(prefs, app.id).selected().dpi;
            tapControl(grid, appTile.findViewById(R.id.appWidgetDpiPlus), false);
            assertTrue(AppWidgetStore.find(prefs, app.id).selected().dpi > originalDpi);
            tapControl(grid, appTile.findViewById(R.id.appWidgetDpiMinus), false);
            assertEquals(originalDpi, AppWidgetStore.find(prefs, app.id).selected().dpi);
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

    private static void bindNativeService() {
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new android.content.ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.MyService"),
                        new android.os.Messenger(new android.os.Handler(Looper.getMainLooper()))
                                .getBinder());
    }

    @Test
    public void emptyDesktopHoldEntersAndOnlyTapExitsEditing() {
        bindNativeService();
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            View desktop = controller.get().findViewById(R.id.mainContent);
            DashboardEditGrid grid = controller.get().findViewById(R.id.splitTilesGrid);
            shadowOf(Looper.getMainLooper()).idle();
            desktop.measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            desktop.layout(0, 0, 1920, 720);
            Duration holdTime = Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1);
            // One point in the grid padding, one in the wallpaper beside the scroll view.
            int gridX = desktop.getPaddingLeft() + 1;
            int y = desktop.getPaddingTop() + 1;
            touch(desktop, MotionEvent.ACTION_DOWN, gridX, y);
            shadowOf(Looper.getMainLooper()).idleFor(holdTime);
            touch(desktop, MotionEvent.ACTION_UP, gridX, y);
            assertTrue(grid.isEditing());

            touch(desktop, MotionEvent.ACTION_DOWN, 4, y);
            touch(desktop, MotionEvent.ACTION_MOVE, 65, y);
            touch(desktop, MotionEvent.ACTION_UP, 65, y);
            assertTrue(grid.isEditing());
            touch(desktop, MotionEvent.ACTION_DOWN, 4, y);
            touch(desktop, MotionEvent.ACTION_CANCEL, 4, y);
            assertTrue(grid.isEditing());
            touch(desktop, MotionEvent.ACTION_DOWN, 4, y);
            touch(desktop, MotionEvent.ACTION_UP, 4, y);
            assertFalse(grid.isEditing());

            touch(desktop, MotionEvent.ACTION_DOWN, 4, y);
            shadowOf(Looper.getMainLooper()).idleFor(holdTime);
            touch(desktop, MotionEvent.ACTION_UP, 4, y);
            assertTrue(grid.isEditing());
            touch(desktop, MotionEvent.ACTION_DOWN, gridX, y);
            shadowOf(Looper.getMainLooper()).idleFor(holdTime);
            touch(desktop, MotionEvent.ACTION_UP, gridX, y);
            assertTrue(grid.isEditing());
            touch(desktop, MotionEvent.ACTION_DOWN, gridX, y);
            touch(desktop, MotionEvent.ACTION_UP, gridX, y);
            assertFalse(grid.isEditing());
        }
    }

    private static void assertControlReceivesTap(DashboardEditGrid grid, View control) {
        boolean[] clicked = {false};
        control.setOnClickListener(view -> clicked[0] = true);
        tapControl(grid, control, false);
        assertTrue(control.getResources().getResourceEntryName(control.getId()), clicked[0]);
    }

    private static void assertControlReceivesHold(DashboardEditGrid grid, View control) {
        boolean[] held = {false};
        control.setOnLongClickListener(
                view -> {
                    held[0] = true;
                    return true;
                });
        tapControl(grid, control, true);
        assertTrue(control.getResources().getResourceEntryName(control.getId()), held[0]);
    }

    private static void tapControl(DashboardEditGrid grid, View control, boolean longPress) {
        android.graphics.Rect bounds =
                new android.graphics.Rect(0, 0, control.getWidth(), control.getHeight());
        grid.offsetDescendantRectToMyCoords(control, bounds);
        touch(grid, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
        if (longPress) {
            shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        }
        touch(grid, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY());
        shadowOf(Looper.getMainLooper()).idle();
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
