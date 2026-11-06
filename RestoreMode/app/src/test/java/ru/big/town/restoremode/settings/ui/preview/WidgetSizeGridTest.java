package ru.big.town.restoremode.settings.ui.preview;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

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
        WidgetSizeGrid picker = new WidgetSizeGrid(context, Robolectric.buildAttributeSet()
                .addAttribute(R.attr.settingsTileKey, HeadlightWidgetLayout.ID)
                .addAttribute(R.attr.settingsTileWidth, "4")
                .addAttribute(R.attr.settingsTileHeight, "1").build());
        picker.usePreferences(profile);
        View grid = picker.getChildAt(picker.getChildCount() - 1);
        grid.layout(0, 0, 600, 250);
        touch(grid, MotionEvent.ACTION_DOWN, 4, 1);
        touch(grid, MotionEvent.ACTION_MOVE, 1, 5);
        assertTrue(profile.getAll().isEmpty());
        touch(grid, MotionEvent.ACTION_UP, 1, 5);
        assertArrayEquals(new int[]{1, 5}, TileSizeStore.dimensions(profile, HeadlightWidgetLayout.ID, 4, 1));
        touch(grid, MotionEvent.ACTION_DOWN, 2, 2);
        touch(grid, MotionEvent.ACTION_UP, 3, 3);
        touch(grid, MotionEvent.ACTION_DOWN, 4, 1);
        touch(grid, MotionEvent.ACTION_CANCEL, 4, 1);
        assertArrayEquals(new int[]{1, 5}, TileSizeStore.dimensions(profile, HeadlightWidgetLayout.ID, 4, 1));
        assertTrue(driver.getAll().isEmpty());
    }

    private void touch(View grid, int action, int column, int row) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, column * 50 - 25, row * 50 - 25, 0);
        grid.dispatchTouchEvent(event);
        event.recycle();
    }
}
