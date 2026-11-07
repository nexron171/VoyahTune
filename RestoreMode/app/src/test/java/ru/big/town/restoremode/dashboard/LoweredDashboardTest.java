package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemProperties;

import ru.big.town.restoremode.MainActivity;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.DashboardEditGrid;
import ru.big.town.restoremode.dashboard.tiles.FittedWidgetFrame;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.energy.VehicleWidgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w1920dp-h720dp-land-mdpi", application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LoweredDashboardTest {
    private final List<Bundle> surfaceRequests = new ArrayList<>();

    @Before
    public void bindNativeService() {
        Messenger service =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    if (message.getData().getBoolean("embeddedSurface")) {
                                        surfaceRequests.add(new Bundle(message.getData()));
                                    }
                                    return true;
                                }));
        shadowOf(RuntimeEnvironment.getApplication())
                .setComponentNameAndServiceForBindService(
                        new ComponentName("ru.big.town.anative", "ru.big.town.anative.MyService"),
                        service.getBinder());
    }

    @Test
    public void firstLoweringUsesFourRowsAndPacksShorterTilesBesideTallTile() {
        SharedPreferences preferences = preferences();
        AppWidgetStore.Entry tall = addApp(preferences, 4, 5);
        AppWidgetStore.Entry upper = addApp(preferences, 2, 2);
        AppWidgetStore.Entry lower = addApp(preferences, 3, 2);
        TileOrderStore.save(
                preferences, Arrays.asList(appTile(tall), appTile(upper), appTile(lower)));
        setLift(1);

        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = grid(controller);
            assertEquals(560, controller.get().findViewById(R.id.main).getLayoutParams().height);
            assertEquals(4, grid.getRowCount());
            View tallView = tile(grid, tall);
            View upperView = tile(grid, upper);
            View lowerView = tile(grid, lower);
            assertTrue(upperView.getLeft() >= tallView.getRight());
            assertEquals(upperView.getLeft(), lowerView.getLeft());
            assertTrue(lowerView.getTop() >= upperView.getBottom());
            assertTrue(tallView.getBottom() <= grid.getHeight() - grid.getPaddingBottom());
            assertEquals(5, AppWidgetStore.find(preferences, tall.id).height);
            assertFalse(preferences.contains("loweredTileLayout"));
        }
    }

    @Test
    public void smallModeResizeSurvivesRecreationAndLeavesRaisedLayoutUntouched() {
        SharedPreferences preferences = preferences();
        AppWidgetStore.Entry app = addApp(preferences, 4, 5);
        TileOrderStore.save(preferences, Arrays.asList(appTile(app)));
        setLift(1);

        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = grid(controller);
            String raisedOrder = preferences.getString(TileOrderStore.ORDER_KEY, "");
            grid.setEditing(true);
            layout(grid);
            View view = tile(grid, app);
            int originalWidth = view.getWidth();
            float column = (view.getWidth() + 8f) / 4;
            float x = view.getRight() - 8;
            float y = view.getBottom() - 8;
            touch(grid, MotionEvent.ACTION_DOWN, x, y);
            touch(grid, MotionEvent.ACTION_UP, x + column, y + 300);
            layout(grid);
            assertTrue(tile(grid, app).getWidth() > originalWidth);
            assertEquals(4, AppWidgetStore.find(preferences, app.id).width);
            assertEquals(5, AppWidgetStore.find(preferences, app.id).height);
            assertTrue(preferences.contains("loweredTileLayout"));
            String smallLayout = preferences.getString("loweredTileLayout", "");
            int smallWidth = tile(grid, app).getWidth();

            controller.recreate();
            grid = grid(controller);
            assertEquals(smallWidth, tile(grid, app).getWidth());
            setLift(2);
            RuntimeEnvironment.getApplication()
                    .sendBroadcast(new Intent("action.qg.layout.changed").putExtra("type", 2));
            shadowOf(Looper.getMainLooper()).idle();
            layout(grid);
            assertEquals(5, grid.getRowCount());
            assertEquals(originalWidth, tile(grid, app).getWidth());
            assertEquals(smallLayout, preferences.getString("loweredTileLayout", ""));
            assertEquals(raisedOrder, preferences.getString(TileOrderStore.ORDER_KEY, ""));
        }
    }

    @Test
    public void resumingAfterPanelMovementRefreshesTheGrid() {
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = grid(controller);
            assertEquals(5, grid.getRowCount());
            controller.pause();
            setLift(1);
            controller.resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, grid.getRowCount());
        }
    }

    @Test
    public void debugButtonTogglesBothSizesWithoutChangingTheOemState() {
        setLift(2);
        ShadowSystemProperties.override("persist.qg.canbus.bcm_screenAutoLiftFdb", "2");
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = grid(controller);
            Button button = controller.get().findViewById(R.id.dashboardDebugScreenSize);
            assertNotNull(button);
            assertEquals("Экран: 720 → 560", button.getText().toString());
            button.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, grid.getRowCount());
            assertEquals(560, controller.get().findViewById(R.id.main).getLayoutParams().height);
            assertEquals("Экран: 560 → 720", button.getText().toString());
            assertEquals(
                    2,
                    Settings.Global.getInt(
                            controller.get().getContentResolver(),
                            "voyahtune_screen_lift_type",
                            0));
            button.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(5, grid.getRowCount());
            assertEquals("Экран: 720 → 560", button.getText().toString());
        }
    }

    @Test
    public void debugSizeSurvivesResumeOemEventsAndActivityRecreation() {
        setLift(2);
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            Button button = controller.get().findViewById(R.id.dashboardDebugScreenSize);
            assertNotNull(button);
            button.performClick();
            controller.pause().resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, grid(controller).getRowCount());
            RuntimeEnvironment.getApplication()
                    .sendBroadcast(new Intent("action.qg.layout.changed").putExtra("type", 2));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(4, grid(controller).getRowCount());
            controller.recreate();
            assertEquals(4, grid(controller).getRowCount());
            button = controller.get().findViewById(R.id.dashboardDebugScreenSize);
            assertEquals("Экран: 560 → 720", button.getText().toString());
            button.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(5, grid(controller).getRowCount());
        }
        try (ActivityController<MainActivity> reopened =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(5, grid(reopened).getRowCount());
        }
    }

    @Test
    public void builtinsKeepFiveRowContentWithAndWithoutAFourRowVariant() {
        SharedPreferences preferences = preferences();
        preferences.edit().putBoolean("show_" + VehicleWidgets.ENERGY, true).apply();
        setLift(1);
        for (int columns : new int[] {3, 4}) {
            TileSizeStore.setSize(preferences, VehicleWidgets.ENERGY, columns, 5);
            try (ActivityController<MainActivity> controller =
                    Robolectric.buildActivity(MainActivity.class).setup()) {
                DashboardEditGrid grid = grid(controller);
                View view = tile(grid, "widget:" + VehicleWidgets.ENERGY);
                assertTrue(view instanceof FittedWidgetFrame);
                View content = ((FittedWidgetFrame) view).content();
                assertTrue(content.getHeight() > view.getHeight());
                assertTrue(content.getScaleY() < 1);
                assertEquals(content.getScaleY(), content.getScaleX(), .001f);
                assertArrayEquals(
                        new int[] {columns, 5},
                        TileSizeStore.dimensions(preferences, VehicleWidgets.ENERGY, 8, 4));
                assertFalse(preferences.contains("loweredTileLayout"));
            }
        }
    }

    @Test
    public void embeddedAppsReceiveTheCurrentGridSizeOnBothPanelPositions() {
        SharedPreferences preferences = preferences();
        AppWidgetStore.Entry app = addApp(preferences, 4, 5);
        setLift(1);
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            DashboardEditGrid grid = grid(controller);
            View window = tile(grid, app);
            ((ViewGroup) window.findViewById(R.id.appWidgetList)).getChildAt(0).performClick();
            attachSurface(grid, window);
            assertEquals(
                    new Size(4, 4),
                    surfaceRequests.get(surfaceRequests.size() - 1).getSize("widgetGridSize"));
            setLift(2);
            RuntimeEnvironment.getApplication()
                    .sendBroadcast(new Intent("action.qg.layout.changed").putExtra("type", 2));
            shadowOf(Looper.getMainLooper()).idle();
            attachSurface(grid, tile(grid, app));
            assertEquals(
                    new Size(4, 5),
                    surfaceRequests.get(surfaceRequests.size() - 1).getSize("widgetGridSize"));
            assertEquals(5, AppWidgetStore.find(preferences, app.id).height);
        }
    }

    private static void attachSurface(DashboardEditGrid grid, View window) {
        layout(grid);
        ViewGroup root = window.findViewById(R.id.appWidgetRoot);
        for (int index = 0; index < root.getChildCount(); index++) {
            if (root.getChildAt(index) instanceof TextureView) {
                TextureView texture = (TextureView) root.getChildAt(index);
                SurfaceTexture surface = new SurfaceTexture(0);
                texture.getSurfaceTextureListener()
                        .onSurfaceTextureAvailable(
                                surface, texture.getWidth(), texture.getHeight());
                shadowOf(Looper.getMainLooper()).idle();
                surface.release();
                return;
            }
        }
        throw new AssertionError("Missing embedded surface");
    }

    private static SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("DrivePreferences", 0);
    }

    private static AppWidgetStore.Entry addApp(
            SharedPreferences preferences, int width, int height) {
        AppWidgetStore.Entry entry =
                AppWidgetStore.add(
                        preferences, RuntimeEnvironment.getApplication().getPackageName());
        entry.width = width;
        entry.height = height;
        AppWidgetStore.update(preferences, entry);
        return entry;
    }

    private static TileOrderStore.Tile appTile(AppWidgetStore.Entry entry) {
        return new TileOrderStore.Tile(TileOrderStore.Tile.TYPE_APP_WIDGET, entry.id);
    }

    private static void setLift(int type) {
        Settings.Global.putInt(
                RuntimeEnvironment.getApplication().getContentResolver(),
                "voyahtune_screen_lift_type",
                type);
    }

    private static DashboardEditGrid grid(ActivityController<MainActivity> controller) {
        shadowOf(Looper.getMainLooper()).idle();
        DashboardEditGrid grid = controller.get().findViewById(R.id.splitTilesGrid);
        layout(grid);
        return grid;
    }

    private static void layout(DashboardEditGrid grid) {
        View main = grid.getRootView().findViewById(R.id.main);
        View content = grid.getRootView().findViewById(R.id.mainContent);
        int viewport =
                main.getLayoutParams().height > 0
                        ? main.getLayoutParams().height
                        : main.getResources().getDisplayMetrics().heightPixels;
        int height = viewport - content.getPaddingTop() - content.getPaddingBottom();
        grid.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, grid.getMeasuredWidth(), grid.getMeasuredHeight());
    }

    private static View tile(ViewGroup grid, AppWidgetStore.Entry entry) {
        return tile(grid, "appWidget:" + entry.id);
    }

    private static View tile(ViewGroup grid, String name) {
        for (int index = 0; index < grid.getChildCount(); index++) {
            View child = grid.getChildAt(index);
            if (name.equals(child.getTransitionName())) {
                return child;
            }
        }
        throw new AssertionError("Missing tile " + name);
    }

    private static void touch(View view, int action, float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}
