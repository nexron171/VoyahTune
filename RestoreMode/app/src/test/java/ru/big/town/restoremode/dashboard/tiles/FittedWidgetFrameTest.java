package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "mdpi", application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FittedWidgetFrameTest {
    @Test
    public void originalCompositionFitsWithoutClippingOrChangingProportions() {
        View content = new View(RuntimeEnvironment.getApplication());
        FittedWidgetFrame frame = new FittedWidgetFrame(content, 500);
        layout(frame, 300, 400);
        assertEquals(500, content.getHeight());
        assertEquals(300, content.getWidth());
        assertEquals(.8f, content.getScaleX(), .001f);
        assertEquals(content.getScaleX(), content.getScaleY(), .001f);
        assertEquals(30, content.getTranslationX(), .001f);
        assertEquals(400, content.getY() + content.getHeight() * content.getScaleY(), .001f);
        layout(frame, 300, 300);
        assertEquals(500, content.getHeight());
        assertEquals(.6f, content.getScaleY(), .001f);
    }

    @Test
    public void nestedButtonsReceiveClicksInTheirScaledCoordinates() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout content = new FrameLayout(context);
        Button button = bottomButton(content);
        boolean[] clicked = {false};
        button.setOnClickListener(view -> clicked[0] = true);
        FittedWidgetFrame frame = new FittedWidgetFrame(content, 500);
        try (ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup()) {
            controller.get().setContentView(frame);
            layout(frame, 300, 400);
            assertTrue(touch(frame, MotionEvent.ACTION_DOWN, 150, 380));
            touch(frame, MotionEvent.ACTION_UP, 150, 380);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(clicked[0]);
        }
    }

    @Test
    public void desktopEditorRespectsTheScaledButtonsOwnHoldAction() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout content = new FrameLayout(context);
        Button button = bottomButton(content);
        boolean[] held = {false};
        button.setOnLongClickListener(
                view -> {
                    held[0] = true;
                    return true;
                });
        FittedWidgetFrame frame = new FittedWidgetFrame(content, 500);
        DashboardEditGrid grid = new DashboardEditGrid(context, null);
        grid.setRowCount(4);
        grid.setActions(mock(DashboardEditGrid.Actions.class));
        grid.beginLayout(100, 100);
        GridLayout.LayoutParams parameters = new GridLayout.LayoutParams();
        parameters.width = 300;
        parameters.height = 400;
        grid.addView(frame, parameters);
        grid.register(frame, new TileOrderStore.Tile("widget", "example"), 3, 4, null);
        try (ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup()) {
            controller.get().setContentView(grid);
            controller.windowFocusChanged(true);
            shadowOf(Looper.getMainLooper()).idle();
            layout(grid, 300, 400);
            touch(grid, MotionEvent.ACTION_DOWN, 150, 380);
            shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() * 2L + 1));
            touch(grid, MotionEvent.ACTION_UP, 150, 380);
            assertTrue(held[0]);
            assertFalse(grid.isEditing());
        }
    }

    private static Button bottomButton(FrameLayout parent) {
        Button button = new Button(parent.getContext());
        button.setFocusable(false);
        FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(-1, 60, Gravity.BOTTOM);
        parent.addView(button, parameters);
        return button;
    }

    private static void layout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static boolean touch(View view, int action, float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        boolean handled = view.dispatchTouchEvent(event);
        event.recycle();
        return handled;
    }
}
