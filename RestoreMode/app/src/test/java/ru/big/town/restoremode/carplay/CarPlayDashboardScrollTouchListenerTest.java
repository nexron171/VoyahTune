package ru.big.town.restoremode.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class CarPlayDashboardScrollTouchListenerTest {
    private final List<Integer> projectedActions = new ArrayList<>();
    private HorizontalScrollView scroll;
    private View tile;
    private CarPlayDashboardScrollTouchListener listener;
    private boolean carPlayActive = true;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        scroll = new HorizontalScrollView(context);
        tile = new View(context);
        scroll.addView(tile, new HorizontalScrollView.LayoutParams(2400, 500));
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 800, 500);
        tile.layout(0, 0, 2400, 500);
        listener = new CarPlayDashboardScrollTouchListener(
                scroll,
                () -> carPlayActive,
                (view, event) -> {
                    projectedActions.add(event.getActionMasked());
                    return true;
                },
                () -> {},
                () -> {});
    }

    @Test
    public void oneFingerRemainsInProjection() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(MotionEvent.ACTION_MOVE, 200);
        touch(MotionEvent.ACTION_UP, 200);
        assertEquals(Arrays.asList(0, 2, 1), projectedActions);
        assertEquals(0, scroll.getScrollX());
    }

    @Test
    public void twoFingersScrollAndSuppressProjectionUntilAllFingersLift() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 400, 500);
        touch(MotionEvent.ACTION_MOVE, 200, 300);
        assertTrue(scroll.getScrollX() > 150);
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), projectedActions);
        int position = scroll.getScrollX();
        touch(MotionEvent.ACTION_POINTER_UP | (1 << 8), 200, 300);
        touch(MotionEvent.ACTION_MOVE, 100);
        touch(MotionEvent.ACTION_UP, 100);
        assertEquals(position, scroll.getScrollX());
        assertEquals(2, projectedActions.size());
        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_UP, 300);
        assertEquals(4, projectedActions.size());
    }

    @Test
    public void pointerChangesDoNotJumpAndCancellationResetsGesture() {
        scroll.scrollTo(500, 0);
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 400, 500);
        touch(MotionEvent.ACTION_MOVE, 402, 502);
        assertEquals(500, scroll.getScrollX());
        touch(MotionEvent.ACTION_POINTER_DOWN | (2 << 8), 402, 502, 700);
        touch(MotionEvent.ACTION_POINTER_UP | (2 << 8), 402, 502, 700);
        touch(MotionEvent.ACTION_MOVE, 402, 502);
        assertEquals(500, scroll.getScrollX());
        touch(MotionEvent.ACTION_MOVE, 602, 702);
        assertTrue(scroll.getScrollX() < 350);
        touch(MotionEvent.ACTION_CANCEL, 602, 702);
        touch(MotionEvent.ACTION_DOWN, 200);
        assertEquals(3, projectedActions.size());
    }

    @Test
    public void otherApplicationsKeepMultitouch() {
        carPlayActive = false;
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 400, 500);
        touch(MotionEvent.ACTION_MOVE, 200, 300);
        assertEquals(Arrays.asList(0, 5, 2), projectedActions);
        assertEquals(0, scroll.getScrollX());
    }

    private void touch(int action, float... positions) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[positions.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[positions.length];
        for (int index = 0; index < positions.length; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = positions[index];
            coordinates[index].y = 100;
        }
        MotionEvent event = MotionEvent.obtain(
                0, 100, action, positions.length, properties, coordinates,
                0, 0, 1, 1, 0, 0, 0, 0);
        event.offsetLocation(scroll.getScrollX(), 0);
        try {
            assertTrue(listener.onTouch(tile, event));
        } finally {
            event.recycle();
        }
    }
}
