package ru.big.town.restoremode.carplay;

import android.graphics.PointF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

import java.util.function.BooleanSupplier;

/** Routes parallel horizontal drags to the dashboard and pinch gestures to CarPlay. */
public final class CarPlayDashboardScrollTouchListener implements View.OnTouchListener {
    private enum GestureOwner {
        UNDECIDED,
        PROJECTION,
        DASHBOARD
    }

    private final HorizontalScrollView scrollView;
    private final BooleanSupplier carPlayActive;
    private final View.OnTouchListener projectionListener;
    private final Runnable onScrollTouch;
    private final Runnable onScrollRelease;
    private final int touchSlop;
    private final SparseArray<PointF> pointerStartPositions = new SparseArray<>();
    private GestureOwner gestureOwner = GestureOwner.UNDECIDED;
    private boolean dragging;
    private float previousScreenX;

    public CarPlayDashboardScrollTouchListener(
            HorizontalScrollView scrollView,
            BooleanSupplier carPlayActive,
            View.OnTouchListener projectionListener,
            Runnable onScrollTouch,
            Runnable onScrollRelease) {
        this.scrollView = scrollView;
        this.carPlayActive = carPlayActive;
        this.projectionListener = projectionListener;
        this.onScrollTouch = onScrollTouch;
        this.onScrollRelease = onScrollRelease;
        touchSlop = ViewConfiguration.get(scrollView.getContext()).getScaledTouchSlop();
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            resetGesture();
        }

        if (gestureOwner == GestureOwner.UNDECIDED && carPlayActive.getAsBoolean()) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                rememberPointerPositions(event);
            } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
                gestureOwner = resolveGestureOwner(event);
                if (gestureOwner == GestureOwner.DASHBOARD) {
                    MotionEvent cancellation = MotionEvent.obtain(event);
                    cancellation.setAction(MotionEvent.ACTION_CANCEL);
                    try {
                        projectionListener.onTouch(view, cancellation);
                    } finally {
                        cancellation.recycle();
                    }
                }
            }
        }

        boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (gestureOwner != GestureOwner.DASHBOARD) {
            boolean handled = projectionListener.onTouch(view, event);
            if (finished) {
                resetGesture();
            }
            return handled;
        }

        ViewParent parent = view.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!finished);
        }

        if (!finished) {
            onScrollTouch.run();
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            int excludedPointer = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            previousScreenX = screenCenterX(event, excludedPointer);
            dragging = false;
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
            float screenX = screenCenterX(event, -1);
            float delta = previousScreenX - screenX;
            if (!dragging && Math.abs(delta) > touchSlop) {
                dragging = true;
                delta -= Math.copySign(touchSlop, delta);
            }
            if (dragging) {
                int distance = Math.round(delta);
                scrollView.scrollBy(distance, 0);
                previousScreenX = screenX;
            }
        }

        if (finished) {
            onScrollRelease.run();
            resetGesture();
        }
        return true;
    }

    private void resetGesture() {
        gestureOwner = GestureOwner.UNDECIDED;
        pointerStartPositions.clear();
        dragging = false;
    }

    private void rememberPointerPositions(MotionEvent event) {
        int excludedPointer =
                event.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        float screenOffsetX = event.getRawX() - event.getX();
        float screenOffsetY = event.getRawY() - event.getY();
        pointerStartPositions.clear();
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index != excludedPointer) {
                pointerStartPositions.put(
                        event.getPointerId(index),
                        new PointF(event.getX(index) + screenOffsetX, event.getY(index) + screenOffsetY));
            }
        }
        previousScreenX = screenCenterX(event, excludedPointer);
    }

    private GestureOwner resolveGestureOwner(MotionEvent event) {
        if (pointerStartPositions.size() < 2) {
            return GestureOwner.UNDECIDED;
        }

        float screenOffsetX = event.getRawX() - event.getX();
        float screenOffsetY = event.getRawY() - event.getY();
        float referenceDeltaX = 0;
        float referenceDeltaY = 0;
        float minimumDeltaX = Float.POSITIVE_INFINITY;
        float maximumDeltaX = Float.NEGATIVE_INFINITY;
        float totalDeltaX = 0;
        float totalDeltaY = 0;

        for (int index = 0; index < event.getPointerCount(); index++) {
            PointF start = pointerStartPositions.get(event.getPointerId(index));
            if (start == null) {
                return GestureOwner.PROJECTION;
            }
            float deltaX = event.getX(index) + screenOffsetX - start.x;
            float deltaY = event.getY(index) + screenOffsetY - start.y;
            if (index == 0) {
                referenceDeltaX = deltaX;
                referenceDeltaY = deltaY;
            }

            // Relative finger motion identifies scaling or rotation even when the center moves.
            if (Math.hypot(deltaX - referenceDeltaX, deltaY - referenceDeltaY) > touchSlop) {
                return GestureOwner.PROJECTION;
            }
            minimumDeltaX = Math.min(minimumDeltaX, deltaX);
            maximumDeltaX = Math.max(maximumDeltaX, deltaX);
            totalDeltaX += deltaX;
            totalDeltaY += deltaY;
        }

        boolean horizontalDrag = minimumDeltaX > touchSlop || maximumDeltaX < -touchSlop;
        if (horizontalDrag && Math.abs(totalDeltaX) > Math.abs(totalDeltaY)) {
            return GestureOwner.DASHBOARD;
        }
        if (Math.abs(totalDeltaY) / event.getPointerCount() > touchSlop
                && Math.abs(totalDeltaY) >= Math.abs(totalDeltaX)) {
            return GestureOwner.PROJECTION;
        }
        return GestureOwner.UNDECIDED;
    }

    private static float screenCenterX(MotionEvent event, int excludedPointer) {
        // Local coordinates move with the tile while its parent scrolls.
        float screenOffset = event.getRawX() - event.getX();
        float total = 0;
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index != excludedPointer) {
                total += event.getX(index) + screenOffset;
                count++;
            }
        }
        return total / count;
    }
}
