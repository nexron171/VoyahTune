package ru.big.town.restoremode.widgets.clock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class ClockWidgetTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void textFitsEveryGridSizeAndGrowsWithAvailableSpace() {
        ClockWidgetView clock = new ClockWidgetView(RuntimeEnvironment.getApplication());
        clock.setLayoutParams(new ViewGroup.LayoutParams(105, 110));
        float smallest = 0;
        for (int columns = 1; columns <= 5; columns++) {
            for (int rows = 1; rows <= 5; rows++) {
                int width = columns * 105;
                int height = rows * 110;
                clock.setText("23:59");
                clock.measure(exact(width), exact(height));
                clock.layout(0, 0, width, height);
                clock.measure(exact(width), exact(height));
                clock.layout(0, 0, width, height);
                int availableWidth = width - clock.getPaddingLeft() - clock.getPaddingRight();
                int availableHeight = height - clock.getPaddingTop() - clock.getPaddingBottom();
                assertEquals(1, clock.getLineCount());
                assertTrue(clock.getPaint().measureText("23:59") <= availableWidth);
                assertTrue(clock.getLayout().getHeight() <= availableHeight);
                if (columns == 1 && rows == 1) {
                    smallest = clock.getTextSize();
                }
            }
        }
        assertTrue(clock.getTextSize() > smallest * 3);
    }

    @Test
    public void gridSizesPersistIndependentlyAndClockIsAvailableInTileOrder() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("clock-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        for (int width = 1; width <= 5; width++) {
            for (int height = 1; height <= 5; height++) {
                TileSizeStore.setWidth(preferences, ClockWidgetView.ID, width);
                TileSizeStore.setHeight(preferences, ClockWidgetView.ID, height);
                assertArrayEquals(
                        new int[] {width, height},
                        TileSizeStore.dimensions(preferences, ClockWidgetView.ID, 1, 1));
            }
        }
        TileSizeStore.setWidth(preferences, ClockWidgetView.ID, 12);
        TileSizeStore.setHeight(preferences, ClockWidgetView.ID, 0);
        assertArrayEquals(
                new int[] {5, 1},
                TileSizeStore.dimensions(preferences, ClockWidgetView.ID, 1, 1));
        TileOrderStore.sync(preferences, RuntimeEnvironment.getApplication().getPackageManager());
        assertTrue(
                TileOrderStore.load(preferences).stream()
                        .anyMatch(tile -> ClockWidgetView.ID.equals(tile.id)));
    }

    private static int exact(int size) {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
    }
}
