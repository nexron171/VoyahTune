package ru.big.town.restoremode.widgets.headlights;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.common.HeadlightWidgetProtocol;
import ru.big.town.restoremode.appearance.AppearanceSettings;
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
    public void buttonBackgroundsFollowWidgetTransparencyWithoutBlurringTheirContent() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("DrivePreferences", 0);
        preferences.edit().putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 50)
                .putBoolean(AppearanceSettings.BLUR_ENABLED, true).commit();
        HeadlightWidgetView widget = new HeadlightWidgetView(context, 4, mode -> {});
        widget.update(state(HeadlightWidgetProtocol.OFF, -1));
        for (int mode = 0; mode < 4; mode++) {
            LinearLayout button = (LinearLayout) widget.getChildAt(mode + 1);
            Bitmap background = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
            button.getBackground().setBounds(0, 0, 100, 100);
            button.getBackground().draw(new Canvas(background));
            assertEquals(128, Color.alpha(background.getPixel(50, 50)));
            assertEquals(1f, button.getAlpha(), 0f);
            assertEquals(1f, button.getChildAt(0).getAlpha(), 0f);
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void allLayoutsExposeFourSeparateButtonsAndPreserveTheirOrder() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        for (HeadlightWidgetLayout.Size size : HeadlightWidgetLayout.Size.values()) {
            List<Integer> selected = new ArrayList<>();
            HeadlightWidgetView widget =
                    new HeadlightWidgetView(context, size.width, selected::add);
            Bundle state = state(HeadlightWidgetProtocol.OFF, -1);
            widget.update(state);
            int width = Math.round(size.width * 105 * density);
            int height = Math.round(size.height * 110 * density);
            widget.measure(exact(width), exact(height));
            widget.layout(0, 0, width, height);
            for (int mode = 0; mode < 4; mode++) {
                View button = widget.getChildAt(mode + 1);
                LinearLayout cell = (LinearLayout) button;
                View symbol = cell.getChildAt(0);
                assertEquals(
                        button.getWidth() / 2f, (symbol.getLeft() + symbol.getRight()) / 2f, 0.5f);
                assertEquals(
                        button.getHeight() / 2f, (symbol.getTop() + symbol.getBottom()) / 2f, 0.5f);
                if (mode == HeadlightWidgetProtocol.OFF || mode == HeadlightWidgetProtocol.AUTO) {
                    assertTrue(symbol instanceof TextView);
                    assertEquals(
                            mode == HeadlightWidgetProtocol.OFF ? "Off" : "A",
                            ((TextView) symbol).getText().toString());
                }
                assertTrue(button.getWidth() >= 48 * density);
                assertTrue(button.getHeight() >= 48 * density);
                assertTrue(button.getRight() <= width);
                assertTrue(button.getBottom() <= height);
                if (mode > 0) {
                    View previous = widget.getChildAt(mode);
                    if (size.width == 1 || (size.width == 2 && mode == 2)) {
                        assertTrue(button.getTop() > previous.getBottom());
                        assertEquals(widget.getChildAt(1).getLeft(), button.getLeft());
                    } else {
                        assertTrue(button.getLeft() > previous.getRight());
                        assertEquals(previous.getTop(), button.getTop());
                    }
                }
                assertEquals(mode == HeadlightWidgetProtocol.OFF, button.isSelected());
                button.performClick();
            }
            assertEquals(Arrays.asList(0, 1, 2, 3), selected);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            widget.draw(new Canvas(bitmap));
            for (int mode = 0; mode < 4; mode++) {
                LinearLayout button = (LinearLayout) widget.getChildAt(mode + 1);
                View symbol = button.getChildAt(0);
                int whitePixels = 0;
                for (int y = button.getTop() + symbol.getTop();
                        y < button.getTop() + symbol.getBottom();
                        y++) {
                    for (int x = button.getLeft() + symbol.getLeft();
                            x < button.getLeft() + symbol.getRight();
                            x++) {
                        int pixel = bitmap.getPixel(x, y);
                        if (Color.red(pixel) > 220
                                && Color.green(pixel) > 220
                                && Color.blue(pixel) > 220) {
                            whitePixels++;
                        }
                    }
                }
                assertTrue("Missing white symbol: " + size + "/" + mode, whitePixels > 10);
            }
            String previewDirectory = System.getenv("VOYAH_WIDGET_PREVIEW_DIR");
            if (previewDirectory != null) {
                File output = new File(previewDirectory, size.width + "x" + size.height + ".png");
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
        for (int columns = 1; columns <= 4; columns++) {
            HeadlightWidgetView widget =
                    new HeadlightWidgetView(
                            RuntimeEnvironment.getApplication(), columns, mode -> {});
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
    }

    @Test
    public void buttonLongPressDelegatesToTileDrag() {
        for (int columns = 1; columns <= 4; columns++) {
            int[] drags = {0};
            List<Integer> commands = new ArrayList<>();
            HeadlightWidgetView widget =
                    new HeadlightWidgetView(
                            RuntimeEnvironment.getApplication(), columns, commands::add);
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
    }

    @Test
    public void sizePresetsPersistWithMatchingHeightAndDoNotChangeOtherTiles() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("headlight-test", 0);
        preferences.edit().clear().commit();
        TileSizeStore.setWidth(preferences, "suspensionWidget", 5);
        for (HeadlightWidgetLayout.Size previous : HeadlightWidgetLayout.Size.values()) {
            for (HeadlightWidgetLayout.Size selected : HeadlightWidgetLayout.Size.values()) {
                TileSizeStore.setHeadlightSize(preferences, previous);
                TileSizeStore.setHeadlightSize(preferences, selected);
                assertArrayEquals(
                        new int[] {selected.width, selected.height},
                        TileSizeStore.dimensions(
                                context.getSharedPreferences("headlight-test", 0),
                                HeadlightWidgetLayout.ID,
                                4,
                                1));
            }
        }
        assertEquals(5, TileSizeStore.width(preferences, "suspensionWidget", 4));
    }

    @Test
    public void defaultAndPreviouslySavedHorizontalSizesKeepTheirDimensions() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("headlight-legacy", 0);
        preferences.edit().clear().commit();
        assertArrayEquals(
                new int[] {4, 1},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        for (int columns = 2; columns <= 4; columns++) {
            preferences.edit().putInt("tileWidth_" + HeadlightWidgetLayout.ID, columns).commit();
            assertArrayEquals(
                    new int[] {columns, columns == 2 ? 2 : 1},
                    TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
            TileSizeStore.setHeight(preferences, HeadlightWidgetLayout.ID, 5);
            assertArrayEquals(
                    new int[] {columns, columns == 2 ? 2 : 1},
                    TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        }
    }

    @Test
    public void verticalSizesRetainTheirHeightAndClampToSupportedDimensions() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("headlight-vertical", 0);
        preferences.edit().clear().commit();
        TileSizeStore.setWidth(preferences, HeadlightWidgetLayout.ID, 1);
        assertArrayEquals(
                new int[] {1, 3},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        TileSizeStore.setHeight(preferences, HeadlightWidgetLayout.ID, 4);
        assertArrayEquals(
                new int[] {1, 4},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        TileSizeStore.setWidth(preferences, HeadlightWidgetLayout.ID, 1);
        assertArrayEquals(
                new int[] {1, 4},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        TileSizeStore.setHeight(preferences, HeadlightWidgetLayout.ID, 1);
        assertArrayEquals(
                new int[] {1, 3},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
        TileSizeStore.setHeight(preferences, HeadlightWidgetLayout.ID, 5);
        assertArrayEquals(
                new int[] {1, 4},
                TileSizeStore.dimensions(preferences, HeadlightWidgetLayout.ID, 4, 1));
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
