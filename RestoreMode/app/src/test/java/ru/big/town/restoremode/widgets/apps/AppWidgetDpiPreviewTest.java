package ru.big.town.restoremode.widgets.apps;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AppWidgetDpiPreviewTest {
    @Test
    public void editsShowLatestValueAndApplyOnceThreeSecondsAfterLastTap() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        List<Integer> applied = new ArrayList<>();
        AppWidgetDpiPreview preview = new AppWidgetDpiPreview(
                new Handler(Looper.getMainLooper()), label, 200, applied::add);
        preview.change(1);
        assertEquals(View.VISIBLE, label.getVisibility());
        idle(2000);
        preview.change(1);
        idle(2999);
        assertEquals(List.of(), applied);
        assertEquals("DPI: 240", label.getText().toString());
        idle(1);
        assertEquals(List.of(240), applied);
        assertEquals(View.GONE, label.getVisibility());
        idle(3000);
        assertEquals(List.of(240), applied);
    }

    @Test
    public void returningToCurrentValueDoesNotApplyAndClosingCancelsPendingChange() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        List<Integer> applied = new ArrayList<>();
        AppWidgetDpiPreview preview = new AppWidgetDpiPreview(
                new Handler(Looper.getMainLooper()), label, 200, applied::add);
        preview.change(1);
        preview.change(-1);
        idle(3000);
        assertEquals(List.of(), applied);
        assertEquals(View.GONE, label.getVisibility());
        preview.change(-1);
        preview.close();
        idle(3000);
        assertEquals(List.of(), applied);
        assertEquals(View.GONE, label.getVisibility());
    }

    @Test
    public void autoAndUpperLimitStayWithinSupportedValues() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        List<Integer> applied = new ArrayList<>();
        AppWidgetDpiPreview preview = new AppWidgetDpiPreview(
                new Handler(Looper.getMainLooper()), label, 0, applied::add);
        preview.change(-1);
        assertEquals("DPI: Авто", label.getText().toString());
        idle(3000);
        assertEquals(List.of(), applied);
        int maximum = AppWidgetStore.DPI_VALUES[AppWidgetStore.DPI_VALUES.length - 1];
        preview = new AppWidgetDpiPreview(
                new Handler(Looper.getMainLooper()), label, maximum, applied::add);
        preview.change(1);
        idle(3000);
        assertEquals(List.of(), applied);
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
