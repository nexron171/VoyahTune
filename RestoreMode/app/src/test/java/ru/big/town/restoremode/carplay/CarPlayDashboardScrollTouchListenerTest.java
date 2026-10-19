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
    private static final int SECOND_POINTER_DOWN =
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    private static final int SECOND_POINTER_UP =
            MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    private static final int THIRD_POINTER_DOWN =
            MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    private static final int THIRD_POINTER_UP =
            MotionEvent.ACTION_POINTER_UP | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);

    private final List<Integer> projectedActions = new ArrayList<>();
    private HorizontalScrollView scrollView;
    private View widgetView;
    private CarPlayDashboardScrollTouchListener listener;
    private boolean carPlayActive = true;
    private int scrollTouches;
    private int scrollReleases;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        scrollView = new HorizontalScrollView(context);
        widgetView = new View(context);
        scrollView.addView(widgetView, new HorizontalScrollView.LayoutParams(2400, 500));
        scrollView.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        scrollView.layout(0, 0, 800, 500);
        widgetView.layout(0, 0, 2400, 500);
        listener = new CarPlayDashboardScrollTouchListener(
                scrollView,
                () -> carPlayActive,
                (view, event) -> {
                    projectedActions.add(event.getAction());
                    return true;
                },
                () -> scrollTouches++,
                () -> scrollReleases++);
    }

    @Test
    public void oneFingerRemainsInProjection() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(MotionEvent.ACTION_MOVE, 200);
        touch(MotionEvent.ACTION_UP, 200);
        assertProjectedActions(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP);
        assertDashboardUntouched();
    }

    @Test
    public void twoFingerTapAndJitterRemainInProjection() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 402, 502);
        touch(SECOND_POINTER_UP, 402, 502);
        touch(MotionEvent.ACTION_UP, 402);

        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                SECOND_POINTER_UP, MotionEvent.ACTION_UP);
        assertDashboardUntouched();
    }

    @Test
    public void pinchOutRemainsInProjection() {
        assertPinchRemainsInProjection(new float[] {300, 100, 500, 100}, new float[] {200, 100, 600, 100});
    }

    @Test
    public void pinchInRemainsInProjection() {
        assertPinchRemainsInProjection(new float[] {200, 100, 600, 100}, new float[] {300, 100, 500, 100});
    }

    @Test
    public void verticalPinchRemainsInProjection() {
        assertPinchRemainsInProjection(new float[] {400, 200, 400, 400}, new float[] {400, 100, 400, 500});
    }

    @Test
    public void diagonalPinchRemainsInProjection() {
        assertPinchRemainsInProjection(new float[] {200, 200, 400, 400}, new float[] {100, 100, 500, 500});
    }

    @Test
    public void asymmetricPinchKeepsProjectionDuringLaterDragAndPointerChanges() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 400, 700);
        touch(MotionEvent.ACTION_MOVE, 200, 500);
        touch(SECOND_POINTER_UP, 200, 500);
        touch(SECOND_POINTER_DOWN, 200, 300);
        touch(MotionEvent.ACTION_MOVE, 100, 200);
        touch(SECOND_POINTER_UP, 100, 200);
        touch(MotionEvent.ACTION_MOVE, 50);
        touch(MotionEvent.ACTION_UP, 50);

        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE, SECOND_POINTER_UP, SECOND_POINTER_DOWN,
                MotionEvent.ACTION_MOVE, SECOND_POINTER_UP, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP);
        assertDashboardUntouched();
    }

    @Test
    public void verticalDragKeepsProjectionDuringLaterHorizontalMovement() {
        touchAt(MotionEvent.ACTION_DOWN, 400, 100);
        touchAt(SECOND_POINTER_DOWN, 400, 100, 500, 100);
        touchAt(MotionEvent.ACTION_MOVE, 400, 300, 500, 300);
        touchAt(MotionEvent.ACTION_MOVE, 200, 300, 300, 300);
        touchAt(SECOND_POINTER_UP, 200, 300, 300, 300);
        touchAt(MotionEvent.ACTION_UP, 200, 300);

        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE, SECOND_POINTER_UP, MotionEvent.ACTION_UP);
        assertDashboardUntouched();
    }

    @Test
    public void twoFingersScrollAndSuppressProjectionUntilAllFingersLift() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 200, 300);
        assertTrue(scrollView.getScrollX() > 150);
        assertProjectedActions(MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_CANCEL);
        int position = scrollView.getScrollX();
        touch(SECOND_POINTER_UP, 200, 300);
        touch(MotionEvent.ACTION_MOVE, 100);
        touch(MotionEvent.ACTION_UP, 100);
        assertEquals(position, scrollView.getScrollX());
        assertEquals(3, projectedActions.size());
        assertTrue(scrollTouches > 0);
        assertEquals(1, scrollReleases);

        touch(MotionEvent.ACTION_DOWN, 300);
        touch(MotionEvent.ACTION_UP, 300);
        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP);
    }

    @Test
    public void pointerChangesDoNotJumpAndCancellationResetsGesture() {
        scrollView.scrollTo(500, 0);
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 402, 502);
        assertEquals(500, scrollView.getScrollX());
        touch(THIRD_POINTER_DOWN, 402, 502, 700);
        touch(THIRD_POINTER_UP, 402, 502, 700);
        touch(MotionEvent.ACTION_MOVE, 402, 502);
        assertEquals(500, scrollView.getScrollX());
        touch(MotionEvent.ACTION_MOVE, 602, 702);
        assertTrue(scrollView.getScrollX() < 350);

        int position = scrollView.getScrollX();
        touch(THIRD_POINTER_DOWN, 602, 702, 800);
        touch(THIRD_POINTER_UP, 602, 702, 800);
        touch(MotionEvent.ACTION_MOVE, 602, 702);
        assertEquals(position, scrollView.getScrollX());
        touch(MotionEvent.ACTION_CANCEL, 602, 702);
        touch(MotionEvent.ACTION_DOWN, 200);
        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                THIRD_POINTER_DOWN, THIRD_POINTER_UP, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN);
        assertEquals(1, scrollReleases);
    }

    @Test
    public void cancelledPinchAllowsDashboardDragInNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 300, 600);
        touch(MotionEvent.ACTION_CANCEL, 300, 600);
        assertDashboardUntouched();

        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 200, 300);
        assertTrue(scrollView.getScrollX() > 150);
        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN,
                MotionEvent.ACTION_CANCEL);
    }

    @Test
    public void otherApplicationsKeepMultitouch() {
        carPlayActive = false;
        touch(MotionEvent.ACTION_DOWN, 400);
        touch(SECOND_POINTER_DOWN, 400, 500);
        touch(MotionEvent.ACTION_MOVE, 200, 300);
        assertProjectedActions(MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE);
        assertDashboardUntouched();
    }

    private void assertPinchRemainsInProjection(float[] start, float[] end) {
        touchAt(MotionEvent.ACTION_DOWN, start[0], start[1]);
        touchAt(SECOND_POINTER_DOWN, start);
        touchAt(MotionEvent.ACTION_MOVE, end);
        touchAt(SECOND_POINTER_UP, end);
        touchAt(MotionEvent.ACTION_UP, end[0], end[1]);

        assertProjectedActions(
                MotionEvent.ACTION_DOWN, SECOND_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                SECOND_POINTER_UP, MotionEvent.ACTION_UP);
        assertDashboardUntouched();
    }

    private void assertProjectedActions(Integer... actions) {
        assertEquals(Arrays.asList(actions), projectedActions);
    }

    private void assertDashboardUntouched() {
        assertEquals(0, scrollView.getScrollX());
        assertEquals(0, scrollTouches);
        assertEquals(0, scrollReleases);
    }

    private void touch(int action, float... positions) {
        float[] coordinates = new float[positions.length * 2];
        for (int index = 0; index < positions.length; index++) {
            coordinates[index * 2] = positions[index];
            coordinates[index * 2 + 1] = 100;
        }
        touchAt(action, coordinates);
    }

    private void touchAt(int action, float... positions) {
        int pointerCount = positions.length / 2;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointerCount];
        for (int index = 0; index < pointerCount; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index;
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = positions[index * 2];
            coordinates[index].y = positions[index * 2 + 1];
        }
        MotionEvent event = MotionEvent.obtain(
                0, 100, action, pointerCount, properties, coordinates,
                0, 0, 1, 1, 0, 0, 0, 0);
        event.offsetLocation(scrollView.getScrollX(), 0);
        try {
            assertTrue(listener.onTouch(widgetView, event));
        } finally {
            event.recycle();
        }
    }
}
