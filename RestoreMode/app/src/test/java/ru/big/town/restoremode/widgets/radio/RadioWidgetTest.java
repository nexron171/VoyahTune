package ru.big.town.restoremode.widgets.radio;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.common.RadioWidgetProtocol;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RadioWidgetTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void allEightSizesKeepSeparateControlsAndOpenTheBody() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (int columns = 2; columns <= 3; columns++) {
            for (int rows = 2; rows <= 5; rows++) {
                List<String> actions = new ArrayList<>();
                RadioWidgetView view =
                        new RadioWidgetView(
                                context, columns, rows, () -> actions.add("open"), actions::add);
                view.update(state(true));
                int width = columns * 147 - 8;
                int height = rows * 127 - 8;
                view.measure(exact(width), exact(height));
                view.layout(0, 0, width, height);
                View previous = find(view, "Предыдущая станция");
                View pause = find(view, "Пауза");
                View next = find(view, "Следующая станция");
                assertTrue(previous.getWidth() >= 48);
                assertTrue(previous.getHeight() >= 48);
                assertTrue(previous.getRight() <= pause.getLeft());
                assertTrue(pause.getRight() <= next.getLeft());
                previous.performClick();
                pause.performClick();
                next.performClick();
                view.performClick();
                assertEquals(java.util.Arrays.asList("previous", "pause", "next", "open"), actions);
                view.update(state(false));
                find(view, "Включить выбранную станцию").performClick();
                assertEquals("play", actions.get(4));
                assertTrue(view.getContentDescription().toString().contains("106,2"));
                view.measure(exact(width), exact(height));
                view.layout(0, 0, width, height);
                String renderDirectory = System.getProperty("radio.widget.renderDir");
                if (renderDirectory != null) {
                    Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(image));
                    File output = new File(renderDirectory, columns + "x" + rows + ".png");
                    output.getParentFile().mkdirs();
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    }
                    image.recycle();
                }
            }
        }
    }

    @Test
    public void dimensionsAreClampedWithoutChangingOtherTiles() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("radio-size-test", 0);
        TileSizeStore.setWidth(preferences, RadioWidgetLayout.ID, 12);
        TileSizeStore.setHeight(preferences, RadioWidgetLayout.ID, 1);
        assertArrayEquals(
                new int[] {3, 2},
                TileSizeStore.dimensions(preferences, RadioWidgetLayout.ID, 2, 2));
        for (int columns = 2; columns <= 3; columns++) {
            for (int rows = 2; rows <= 5; rows++) {
                TileSizeStore.setWidth(preferences, RadioWidgetLayout.ID, columns);
                TileSizeStore.setHeight(preferences, RadioWidgetLayout.ID, rows);
                assertArrayEquals(
                        new int[] {columns, rows},
                        TileSizeStore.dimensions(preferences, RadioWidgetLayout.ID, 2, 2));
            }
        }
        assertEquals(4, TileSizeStore.width(preferences, "suspensionWidget", 4));
    }

    @Test
    public void missingDataDisablesCommandsButKeepsRadioOpeningAvailable() {
        List<String> actions = new ArrayList<>();
        RadioWidgetView view =
                new RadioWidgetView(
                        RuntimeEnvironment.getApplication(),
                        2,
                        2,
                        () -> actions.add("open"),
                        actions::add);
        view.update(new Bundle());
        assertFalse(find(view, "Предыдущая станция").isEnabled());
        assertFalse(find(view, "Включить выбранную станцию").isEnabled());
        view.performClick();
        assertEquals(java.util.Collections.singletonList("open"), actions);
    }

    @Test
    public void frequencyUsesRawBandUnits() {
        assertEquals("106,2", RadioWidgetProtocol.frequency(true, 10620));
        assertEquals("999", RadioWidgetProtocol.frequency(false, 999));
        assertEquals("—", RadioWidgetProtocol.frequency(true, -1));
    }

    private static Bundle state(boolean playing) {
        Bundle state = new Bundle();
        state.putBoolean(RadioWidgetProtocol.FM, true);
        state.putInt(RadioWidgetProtocol.FREQUENCY, 10620);
        state.putString(RadioWidgetProtocol.NAME, "Европа Плюс");
        state.putBoolean(RadioWidgetProtocol.READY, true);
        state.putBoolean(RadioWidgetProtocol.PLAYING, playing);
        state.putBoolean(RadioWidgetProtocol.CAN_SKIP, true);
        return state;
    }

    private static View find(View view, String description) {
        if (description.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = find(group.getChildAt(index), description);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static int exact(int size) {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
    }
}
