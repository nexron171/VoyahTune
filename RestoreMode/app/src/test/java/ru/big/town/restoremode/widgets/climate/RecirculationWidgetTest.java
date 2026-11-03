package ru.big.town.restoremode.widgets.climate;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.common.RecirculationWidgetProtocol;
import ru.big.town.restoremode.R;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RecirculationWidgetTest {
    private Bundle state(int mode, boolean pending) {
        Bundle state = new Bundle();
        state.putInt(RecirculationWidgetProtocol.MODE, mode);
        state.putBoolean(RecirculationWidgetProtocol.AVAILABLE, true);
        state.putBoolean(RecirculationWidgetProtocol.PENDING, pending);
        return state;
    }

    @Test
    public void showsSuppliedImageForEveryOemModeAndRequestsCycleOnTap() {
        int[] requests = {0};
        RecirculationWidgetView widget =
                new RecirculationWidgetView(
                        RuntimeEnvironment.getApplication(), () -> requests[0]++);
        int[] modes = {
            RecirculationWidgetProtocol.ON,
            RecirculationWidgetProtocol.OFF,
            RecirculationWidgetProtocol.AUTO
        };
        int[] images = {
            R.drawable.recirculation_on, R.drawable.recirculation_off, R.drawable.recirculation_auto
        };
        String[] labels = {"Вкл", "Выкл", "Авто"};
        for (int index = 0; index < modes.length; index++) {
            widget.update(state(modes[index], false));
            ImageView image = (ImageView) widget.getChildAt(1);
            assertEquals(
                    images[index],
                    shadowOf(((BitmapDrawable) image.getDrawable()).getBitmap())
                            .getCreatedFromResId());
            assertEquals(labels[index], ((TextView) widget.getChildAt(2)).getText());
            widget.performClick();
        }
        assertEquals(3, requests[0]);
    }

    @Test
    public void pendingNeverDisplaysRequestedModeAndUnknownModeNeverLooksOff() {
        int[] requests = {0};
        RecirculationWidgetView widget =
                new RecirculationWidgetView(
                        RuntimeEnvironment.getApplication(), () -> requests[0]++);
        widget.update(state(RecirculationWidgetProtocol.ON, true));
        widget.performClick();
        assertEquals(0, requests[0]);
        assertTrue(((TextView) widget.getChildAt(2)).getText().toString().startsWith("Вкл"));
        widget.update(state(RecirculationWidgetProtocol.AUTO, false));
        assertEquals("Авто", ((TextView) widget.getChildAt(2)).getText());
        Bundle failure = state(RecirculationWidgetProtocol.AUTO, false);
        failure.putString(RecirculationWidgetProtocol.MESSAGE, "Режим не подтверждён автомобилем");
        widget.update(failure);
        assertEquals("Авто · !", ((TextView) widget.getChildAt(2)).getText());
        assertTrue(widget.getContentDescription().toString().contains("не подтверждён"));
        widget.update(new Bundle());
        assertEquals(View.INVISIBLE, widget.getChildAt(1).getVisibility());
        assertEquals("—", ((TextView) widget.getChildAt(2)).getText());
        widget.performClick();
        assertEquals(0, requests[0]);
        boolean[] moved = {false};
        widget.setOnLongClickListener(
                view -> {
                    moved[0] = true;
                    return true;
                });
        assertTrue(widget.performLongClick());
        assertTrue(moved[0]);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void oneCellLayoutKeepsIconAndLabelsVisible() throws Exception {
        RecirculationWidgetView widget =
                new RecirculationWidgetView(RuntimeEnvironment.getApplication(), () -> {});
        float density = widget.getResources().getDisplayMetrics().density;
        int width = Math.round(105 * density);
        int height = Math.round(110 * density);
        for (int mode :
                new int[] {
                    RecirculationWidgetProtocol.ON,
                    RecirculationWidgetProtocol.OFF,
                    RecirculationWidgetProtocol.AUTO
                }) {
            widget.update(state(mode, false));
            widget.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            widget.layout(0, 0, width, height);
            TextView title = (TextView) widget.getChildAt(0);
            View icon = widget.getChildAt(1);
            TextView status = (TextView) widget.getChildAt(2);
            assertEquals(0, title.getLayout().getEllipsisCount(0));
            assertEquals(0, status.getLayout().getEllipsisCount(0));
            assertTrue("Title height: " + title.getHeight(), title.getHeight() >= 12 * density);
            assertTrue("Status height: " + status.getHeight(), status.getHeight() >= 12 * density);
            assertTrue(icon.getHeight() >= 40 * density);
            assertTrue(title.getBottom() <= icon.getTop());
            assertTrue(icon.getBottom() <= status.getTop());
            assertTrue(status.getBottom() <= height - widget.getPaddingBottom());
            String previewDirectory = System.getenv("VOYAH_WIDGET_PREVIEW_DIR");
            if (previewDirectory != null) {
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                widget.draw(new Canvas(bitmap));
                File output = new File(previewDirectory, "recirculation-" + mode + ".png");
                output.getParentFile().mkdirs();
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
                bitmap.recycle();
            }
        }
    }
}
