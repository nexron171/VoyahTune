package ru.big.town.restoremode.carplay;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

import java.util.function.BooleanSupplier;

/** Reserves multi-finger widget drags for the dashboard instead of the projection. */
public final class CarPlayDashboardScrollTouchListener implements View.OnTouchListener {
    private final HorizontalScrollView scrollView;
    private final BooleanSupplier carPlayActive;
    private final View.OnTouchListener projectionListener;
    private final Runnable onScrollTouch;
    private final Runnable onScrollRelease;
    private final int touchSlop;
    private boolean dashboardGesture;
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
            dashboardGesture = false;
            dragging = false;
        }

        if (!dashboardGesture
                && carPlayActive.getAsBoolean()
                && action == MotionEvent.ACTION_POINTER_DOWN
                && event.getPointerCount() >= 2) {
            MotionEvent cancellation = MotionEvent.obtain(event);
            cancellation.setAction(MotionEvent.ACTION_CANCEL);
            try {
                projectionListener.onTouch(view, cancellation);
            } finally {
                cancellation.recycle();
            }
            dashboardGesture = true;
            dragging = false;
            previousScreenX = screenCenterX(event, -1);
        }

        if (!dashboardGesture) {
            return projectionListener.onTouch(view, event);
        }

        boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
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
            dashboardGesture = false;
            dragging = false;
        }
        return true;
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
