package ru.big.town.restoremode.widgets.headlights;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.common.HeadlightWidgetProtocol;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class HeadlightWidgetTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void allLayoutsExposeFourSeparateButtonsAndPreserveTheirOrder() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        for (int columns = 2; columns <= 4; columns++) {
            List<Integer> selected = new ArrayList<>();
            HeadlightWidgetView widget = new HeadlightWidgetView(context, columns, selected::add);
            Bundle state = state(HeadlightWidgetProtocol.OFF, -1);
            widget.update(state);
            int width = Math.round(columns * 105 * density);
            int height = Math.round(HeadlightWidgetLayout.height(columns) * 110 * density);
            widget.measure(exact(width), exact(height));
            widget.layout(0, 0, width, height);
            for (int mode = 0; mode < 4; mode++) {
                View button = widget.getChildAt(mode + 1);
                android.widget.LinearLayout cell = (android.widget.LinearLayout) button;
                View symbol = cell.getChildAt(0);
                assertEquals(
                        button.getWidth() / 2f, (symbol.getLeft() + symbol.getRight()) / 2f, 0.5f);
                assertEquals(
                        button.getHeight() / 2f, (symbol.getTop() + symbol.getBottom()) / 2f, 0.5f);
                if (mode == HeadlightWidgetProtocol.OFF || mode == HeadlightWidgetProtocol.AUTO) {
                    assertTrue(symbol instanceof android.widget.TextView);
                    assertEquals(
                            mode == HeadlightWidgetProtocol.OFF ? "Off" : "A",
                            ((android.widget.TextView) symbol).getText().toString());
                }
                assertTrue(button.getWidth() >= 48 * density);
                assertTrue(button.getHeight() >= 48 * density);
                assertTrue(button.getRight() <= width);
                assertTrue(button.getBottom() <= height);
                assertEquals(mode == HeadlightWidgetProtocol.OFF, button.isSelected());
                button.performClick();
            }
            assertEquals(Arrays.asList(0, 1, 2, 3), selected);
            assertEquals(
                    columns == 2, widget.getChildAt(3).getTop() > widget.getChildAt(1).getTop());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            widget.draw(new Canvas(bitmap));
            for (int mode = 0; mode < 4; mode++) {
                android.widget.LinearLayout button =
                        (android.widget.LinearLayout) widget.getChildAt(mode + 1);
                View symbol = button.getChildAt(0);
                int whitePixels = 0;
                for (int y = button.getTop() + symbol.getTop();
                        y < button.getTop() + symbol.getBottom();
                        y++) {
                    for (int x = button.getLeft() + symbol.getLeft();
                            x < button.getLeft() + symbol.getRight();
                            x++) {
                        int pixel = bitmap.getPixel(x, y);
                        if (android.graphics.Color.red(pixel) > 220
                                && android.graphics.Color.green(pixel) > 220
                                && android.graphics.Color.blue(pixel) > 220) {
                            whitePixels++;
                        }
                    }
                }
                assertTrue("Missing white symbol: " + columns + "/" + mode, whitePixels > 10);
            }
            String previewDirectory = System.getenv("VOYAH_WIDGET_PREVIEW_DIR");
            if (previewDirectory != null) {
                File output =
                        new File(
                                previewDirectory,
                                columns + "x" + HeadlightWidgetLayout.height(columns) + ".png");
                output.getParentFile().mkdirs();
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
            }
            bitmap.recycle();
        }
    }

    @Test
    public void pendingAndDisconnectedStatesDisableCommandsWithoutOptimisticHighlight() {
        HeadlightWidgetView widget =
                new HeadlightWidgetView(RuntimeEnvironment.getApplication(), 3, mode -> {});
        widget.update(state(HeadlightWidgetProtocol.OFF, HeadlightWidgetProtocol.PARKING));
        assertTrue(widget.getChildAt(1).isSelected());
        assertFalse(widget.getChildAt(2).isSelected());
        assertTrue(widget.getChildAt(2).isActivated());
        for (int mode = 0; mode < 4; mode++) {
            assertFalse(widget.getChildAt(mode + 1).isEnabled());
        }
        widget.update(new Bundle());
        for (int mode = 0; mode < 4; mode++) {
            assertFalse(widget.getChildAt(mode + 1).isSelected());
            assertFalse(widget.getChildAt(mode + 1).isEnabled());
        }
    }

    @Test
    public void buttonLongPressDelegatesToTileDrag() {
        int[] drags = {0};
        List<Integer> commands = new ArrayList<>();
        HeadlightWidgetView widget =
                new HeadlightWidgetView(RuntimeEnvironment.getApplication(), 2, commands::add);
        widget.setOnLongClickListener(
                view -> {
                    drags[0]++;
                    return true;
                });
        widget.update(state(0, -1));
        assertTrue(widget.getChildAt(2).performLongClick());
        assertEquals(1, drags[0]);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void sizePresetsPersistWithMatchingHeightAndDoNotChangeOtherTiles() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("headlight-test", 0);
        preferences.edit().clear().commit();
        TileSizeStore.setWidth(preferences, "suspensionWidget", 5);
        for (int columns = 2; columns <= 4; columns++) {
            TileSizeStore.setWidth(preferences, HeadlightWidgetLayout.ID, columns);
            TileSizeStore.setHeight(preferences, HeadlightWidgetLayout.ID, 5);
            assertArrayEquals(
                    new int[] {columns, columns == 2 ? 2 : 1},
                    TileSizeStore.dimensions(
                            context.getSharedPreferences("headlight-test", 0),
                            HeadlightWidgetLayout.ID,
                            4,
                            1));
        }
        assertEquals(5, TileSizeStore.width(preferences, "suspensionWidget", 4));
    }

    private static int exact(int size) {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
    }

    private static Bundle state(int mode, int pending) {
        Bundle state = new Bundle();
        state.putInt(HeadlightWidgetProtocol.MODE, mode);
        state.putInt(HeadlightWidgetProtocol.PENDING, pending);
        state.putBoolean(HeadlightWidgetProtocol.AVAILABLE, pending < 0);
        state.putString(HeadlightWidgetProtocol.MESSAGE, "");
        return state;
    }
}
