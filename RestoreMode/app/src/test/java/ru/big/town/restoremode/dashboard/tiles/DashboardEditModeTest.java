package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Size;
import android.view.MotionEvent;
import android.view.TextureView;
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
import java.util.ArrayList;
import java.util.List;

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
            assertNull(appTile.findViewById(R.id.appWidgetList));
            controller.get().findViewById(R.id.dashboardEditDone).performClick();
            layout(grid);
            appTile = tile(grid, "appWidget:" + app.id);
            assertEquals(0, visibleWindowButtons(appTile));
            holdWidget(grid, appTile);
            assertTrue(grid.isEditing());
            controller.get().findViewById(R.id.dashboardEditDone).performClick();
            appTile = tile(grid, "appWidget:" + app.id);
            ViewGroup profiles = appTile.findViewById(R.id.appWidgetList);
            profiles.getChildAt(0).performClick();
            layout(grid);
            View controls = appTile.findViewById(R.id.appWidgetOverlayControls);
            assertEquals(0, visibleWindowButtons(appTile));
            holdWidget(grid, appTile);
            assertFalse(grid.isEditing());
            assertEquals(View.VISIBLE, controls.getVisibility());
            layout(grid);
            int originalDpi = AppWidgetStore.find(prefs, app.id).selected().dpi;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            tapControl(grid, appTile.findViewById(R.id.appWidgetDpiPlus));
            assertTrue(AppWidgetStore.find(prefs, app.id).selected().dpi > originalDpi);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(View.VISIBLE, controls.getVisibility());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(View.GONE, controls.getVisibility());

            holdWidget(grid, appTile);
            layout(grid);
            tapControl(grid, appTile.findViewById(R.id.appWidgetClose));
            assertEquals(View.GONE, controls.getVisibility());
            assertNotNull(AppWidgetStore.find(prefs, app.id));
            assertEquals(0, visibleWindowButtons(appTile));
            profiles.getChildAt(0).performClick();
            layout(grid);
            holdWidget(grid, appTile);
            layout(grid);
            tapControl(grid, appTile.findViewById(R.id.appWidgetEditDesktop));
            assertTrue(grid.isEditing());
            layout(grid);
            appTile = tile(grid, "appWidget:" + app.id);
            assertNull(appTile.findViewById(R.id.appWidgetOverlayControls));
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

    @Test
    public void holdingAppWidgetBodyRequestsMove() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("DrivePreferences", 0);
        AppWidgetStore.Entry app = AppWidgetStore.add(preferences, context.getPackageName(), "");
        bindNativeService();
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = controller.get().findViewById(R.id.splitTilesGrid);
            shadowOf(Looper.getMainLooper()).idle();
            grid.setEditing(true);
            layout(grid);
            View window = tile(grid, "appWidget:" + app.id);
            DashboardEditGrid.Actions actions = mock(DashboardEditGrid.Actions.class);
            grid.setActions(actions);
            holdWidget(grid, window);
            verify(actions).move(window);
        }
    }

    @Test
    public void appWindowReattachesOnlyAfterEditingWithFinalSize() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("DrivePreferences", 0);
        AppWidgetStore.Entry app = AppWidgetStore.add(preferences, context.getPackageName(), "");
        app.width = 4;
        AppWidgetStore.update(preferences, app);
        List<Bundle> requests = bindNativeService();
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = controller.get().findViewById(R.id.splitTilesGrid);
            shadowOf(Looper.getMainLooper()).idle();
            layout(grid);
            View window = tile(grid, "appWidget:" + app.id);
            ((ViewGroup) window.findViewById(R.id.appWidgetList)).getChildAt(0).performClick();
            attachSurface(grid, window);
            assertEquals(
                    new Size(4, app.height),
                    requests.get(requests.size() - 1).getSize("widgetGridSize"));

            grid.setEditing(true);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(requests.get(requests.size() - 1).getBoolean("embeddedDetach"));
            int parkedRequestCount = requests.size();
            layout(grid);
            window = tile(grid, "appWidget:" + app.id);
            float centerX = window.getX() + window.getWidth() / 2f;
            float centerY = window.getY() + window.getHeight() / 2f;
            touch(grid, MotionEvent.ACTION_DOWN, centerX, centerY);
            touch(grid, MotionEvent.ACTION_UP, centerX, centerY);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(parkedRequestCount, requests.size());
            for (int columns = 4; columns < 6; columns++) {
                layout(grid);
                window = tile(grid, "appWidget:" + app.id);
                float x = window.getRight() - 8;
                float y = window.getBottom() - 8;
                float column = (window.getWidth() + 8f) / columns;
                touch(grid, MotionEvent.ACTION_DOWN, x, y);
                touch(grid, MotionEvent.ACTION_MOVE, x + column, y);
                touch(grid, MotionEvent.ACTION_UP, x + column, y);
                shadowOf(Looper.getMainLooper()).idle();
                assertEquals(columns + 1, AppWidgetStore.find(preferences, app.id).width);
                assertEquals(parkedRequestCount, requests.size());
            }
            controller.get().getOnBackPressedDispatcher().onBackPressed();
            shadowOf(Looper.getMainLooper()).idle();
            window = tile(grid, "appWidget:" + app.id);
            attachSurface(grid, window);
            assertEquals(parkedRequestCount + 1, requests.size());
            Bundle attached = requests.get(requests.size() - 1);
            assertEquals(new Size(6, app.height), attached.getSize("widgetGridSize"));
            assertEquals(context.getPackageName(), attached.getString("left"));
            assertEquals(
                    window.getWidth() - window.getPaddingLeft() - window.getPaddingRight(),
                    attached.getInt("width"));
            assertEquals(
                    window.getHeight() - window.getPaddingTop() - window.getPaddingBottom(),
                    attached.getInt("height"));
        }
    }

    private static int visibleWindowButtons(View view) {
        if (view.getVisibility() != View.VISIBLE) return 0;
        int count = view instanceof android.widget.ImageButton ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += visibleWindowButtons(group.getChildAt(i));
            }
        }
        return count;
    }

    private static void attachSurface(DashboardEditGrid grid, View window) {
        layout(grid);
        ViewGroup root = window.findViewById(R.id.appWidgetRoot);
        for (int index = 0; index < root.getChildCount(); index++) {
            if (root.getChildAt(index) instanceof TextureView) {
                TextureView view = (TextureView) root.getChildAt(index);
                SurfaceTexture texture = new SurfaceTexture(0);
                view.getSurfaceTextureListener()
                        .onSurfaceTextureAvailable(texture, view.getWidth(), view.getHeight());
                shadowOf(Looper.getMainLooper()).idle();
                texture.release();
                return;
            }
        }
        fail("Expected the running app to have a window surface");
    }

    private static List<Bundle> bindNativeService() {
        List<Bundle> requests = new ArrayList<>();
        Messenger service =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    Bundle data = message.getData();
                                    if (data.getBoolean("embeddedSurface")
                                            || data.getBoolean("embeddedDetach")
                                            || data.getBoolean("embeddedTouch")) {
                                        requests.add(new Bundle(data));
                                    }
                                    return true;
                                }));
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new android.content.ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.MyService"),
                        service.getBinder());
        return requests;
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

    private static void holdWidget(DashboardEditGrid grid, View widget) {
        float x = widget.getX() + widget.getWidth() / 2f;
        float y = widget.getY() + widget.getHeight() / 2f;
        touch(grid, MotionEvent.ACTION_DOWN, x, y);
        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        touch(grid, MotionEvent.ACTION_UP, x, y);
    }

    private static void tapControl(DashboardEditGrid grid, View control) {
        android.graphics.Rect bounds =
                new android.graphics.Rect(0, 0, control.getWidth(), control.getHeight());
        grid.offsetDescendantRectToMyCoords(control, bounds);
        touch(grid, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
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
