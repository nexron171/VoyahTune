package ru.big.town.restoremode.settings.ui.preview;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class WidgetSizeGridTest {
    @Test
    public void dragCommitsOnlyValidReleasedSizeToBoundProfile() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences profile = context.getSharedPreferences("Passenger", 0);
        SharedPreferences driver = context.getSharedPreferences("DrivePreferences", 0);
        WidgetSizeGrid picker =
                new WidgetSizeGrid(
                        context,
                        Robolectric.buildAttributeSet()
                                .addAttribute(R.attr.settingsTileKey, HeadlightWidgetLayout.ID)
                                .addAttribute(R.attr.settingsTileWidth, "4")
                                .addAttribute(R.attr.settingsTileHeight, "1")
                                .build());
        picker.usePreferences(profile);
        View grid = picker.getChildAt(picker.getChildCount() - 1);
        grid.layout(0, 0, 600, 250);
        touch(grid, MotionEvent.ACTION_DOWN, 4, 1);
        touch(grid, MotionEvent.ACTION_MOVE, 1, 5);
        assertTrue(profile.getAll().isEmpty());
        touch(grid, MotionEvent.ACTION_UP, 1, 5);
        assertArrayEquals(
                new int[] {1, 5},
                TileSizeStore.dimensions(profile, HeadlightWidgetLayout.ID, 4, 1));
        touch(grid, MotionEvent.ACTION_DOWN, 2, 2);
        touch(grid, MotionEvent.ACTION_UP, 3, 3);
        touch(grid, MotionEvent.ACTION_DOWN, 4, 1);
        touch(grid, MotionEvent.ACTION_CANCEL, 4, 1);
        assertArrayEquals(
                new int[] {1, 5},
                TileSizeStore.dimensions(profile, HeadlightWidgetLayout.ID, 4, 1));
        assertTrue(driver.getAll().isEmpty());
    }

    @Test
    public void suspensionOffersOnlyFourSizesAndRejectsUnsupportedSelections() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences passenger = context.getSharedPreferences("Passenger", 0);
        SharedPreferences driver = context.getSharedPreferences("DrivePreferences", 0);
        String widgetId = TileSizeStore.SUSPENSION_WIDGET_ID;
        WidgetSizeGrid picker =
                new WidgetSizeGrid(
                        context,
                        Robolectric.buildAttributeSet()
                                .addAttribute(R.attr.settingsTileKey, widgetId)
                                .addAttribute(R.attr.settingsTileWidth, "4")
                                .addAttribute(R.attr.settingsTileHeight, "3")
                                .build());
        picker.usePreferences(passenger);
        picker.getChildAt(0).performClick();
        ListAdapter choices = ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter();
        assertEquals(4, choices.getCount());
        assertEquals("3 × 2", choices.getItem(0));
        assertEquals("3 × 3", choices.getItem(1));
        assertEquals("4 × 2", choices.getItem(2));
        assertEquals("4 × 3", choices.getItem(3));
        ShadowAlertDialog.getLatestAlertDialog().dismiss();

        View grid = picker.getChildAt(picker.getChildCount() - 1);
        grid.layout(0, 0, 600, 250);
        for (int[] size : new int[][] {{3, 2}, {3, 3}, {4, 2}, {4, 3}}) {
            touch(grid, MotionEvent.ACTION_DOWN, size[0], size[1]);
            touch(grid, MotionEvent.ACTION_UP, size[0], size[1]);
            assertArrayEquals(size, TileSizeStore.dimensions(passenger, widgetId, 4, 3));
        }
        for (int columns = 1; columns <= 12; columns++) {
            for (int rows = 1; rows <= 5; rows++) {
                boolean supported = (columns == 3 || columns == 4) && (rows == 2 || rows == 3);
                assertEquals(supported, TileSizeStore.supportsSize(widgetId, columns, rows));
                if (supported) continue;
                touch(grid, MotionEvent.ACTION_DOWN, columns, rows);
                touch(grid, MotionEvent.ACTION_UP, columns, rows);
                assertArrayEquals(
                        new int[] {4, 3}, TileSizeStore.dimensions(passenger, widgetId, 4, 3));
            }
        }
        assertTrue(driver.getAll().isEmpty());
    }

    @Test
    public void suspensionClampsLegacyDimensionsAndIndividualSizeUpdates() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("legacy-suspension", Context.MODE_PRIVATE);
        String widgetId = TileSizeStore.SUSPENSION_WIDGET_ID;
        assertArrayEquals(new int[] {4, 3}, TileSizeStore.dimensions(preferences, widgetId, 4, 3));
        for (int[] sample :
                new int[][] {
                    {1, 1, 3, 2}, {2, 5, 3, 3}, {5, 1, 4, 2}, {12, 5, 4, 3}, {-1, 0, 3, 2}
                }) {
            preferences
                    .edit()
                    .putInt("tileWidth_" + widgetId, sample[0])
                    .putInt("tileHeight_" + widgetId, sample[1])
                    .apply();
            int[] expected = {sample[2], sample[3]};
            assertArrayEquals(expected, TileSizeStore.dimensions(preferences, widgetId, 4, 3));
            assertEquals(expected[0], TileSizeStore.width(preferences, widgetId, 4));
            assertEquals(expected[1], TileSizeStore.height(preferences, widgetId, 3));
            TileSizeStore.clear(preferences, widgetId);
            TileSizeStore.setWidth(preferences, widgetId, sample[0]);
            TileSizeStore.setHeight(preferences, widgetId, sample[1]);
            assertArrayEquals(expected, TileSizeStore.dimensions(preferences, widgetId, 4, 3));
            assertEquals(expected[0], preferences.getInt("tileWidth_" + widgetId, 0));
            assertEquals(expected[1], preferences.getInt("tileHeight_" + widgetId, 0));
        }
    }

    private void touch(View grid, int action, int column, int row) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, column * 50 - 25, row * 50 - 25, 0);
        grid.dispatchTouchEvent(event);
        event.recycle();
    }
}
