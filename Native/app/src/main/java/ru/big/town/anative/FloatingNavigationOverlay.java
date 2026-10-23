package ru.big.town.anative;

import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.function.IntConsumer;

/** One movable Back/Home window, using the accessibility token of its display context. */
final class FloatingNavigationOverlay {
    private static final String TAG = "FloatingNavigation";
    private static final int BUTTON_SIZE_DP = 45;
    private static final int BUTTON_GAP_DP = 10;
    private static final int TOUCH_OUTSET_DP = 10;

    private final Context context;
    private final SharedPreferences preferences;
    private final WindowManager windowManager;
    private final IntConsumer action;
    private final int buttonSize;
    private final int buttonGap;
    private final int touchOutset;
    private LinearLayout buttons;
    private WindowManager.LayoutParams layout;

    FloatingNavigationOverlay(Context context, SharedPreferences preferences, IntConsumer action) {
        this.context = context;
        this.preferences = preferences;
        this.action = action;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        buttonSize = dp(BUTTON_SIZE_DP);
        buttonGap = dp(BUTTON_GAP_DP);
        touchOutset = dp(TOUCH_OUTSET_DP);
    }

    void show() {
        if (buttons != null) {
            return;
        }
        if (windowManager == null) return;
        LinearLayout view = new LinearLayout(context);
        view.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        view.setBackground(null);
        view.addView(createButton(R.drawable.ic_back_arrow, R.string.floating_back_button_desc));
        view.addView(createButton(R.drawable.ic_home, R.string.floating_home_button_desc));
        view.setOnTouchListener(new DragTouchListener());
        layout = new WindowManager.LayoutParams(
                buttonSize + touchOutset * 2,
                buttonSize * 2 + buttonGap + touchOutset * 2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.START | Gravity.TOP;
        try {
            windowManager.addView(view, layout);
            buttons = view;
            updatePosition();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot show navigation overlay", error);
        }
    }

    void hide() {
        if (buttons == null || windowManager == null) return;
        try {
            windowManager.removeView(buttons);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot remove navigation overlay", error);
        }
        buttons = null;
    }

    void reshow() {
        hide();
        show();
    }

    private ImageView createButton(int icon, int description) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(context.getString(description));
        int padding = dp(10);
        button.setPadding(padding, padding, padding, padding);
        button.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
        return button;
    }

    void updatePosition() {
        if (buttons == null || windowManager == null || layout == null) return;
        int side = preferences.getInt("floatingBackSide", BackButtonService.SIDE_LEFT);
        int offset = preferences.getInt("floatingBackOffset", -1);
        boolean horizontal = side == BackButtonService.SIDE_TOP;
        buttons.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        buttons.setPadding(touchOutset, touchOutset, touchOutset, touchOutset);
        for (int index = 0; index < buttons.getChildCount(); index++) {
            LinearLayout.LayoutParams child = new LinearLayout.LayoutParams(buttonSize, buttonSize);
            if (index == 0) {
                if (horizontal) child.rightMargin = buttonGap;
                else child.bottomMargin = buttonGap;
            }
            buttons.getChildAt(index).setLayoutParams(child);
        }
        Point size = displaySize();
        int length = buttonSize * 2 + buttonGap + touchOutset * 2;
        int thickness = buttonSize + touchOutset * 2;
        layout.width = horizontal ? length : thickness;
        layout.height = horizontal ? thickness : length;
        layout.gravity = Gravity.START | Gravity.TOP;
        if (horizontal) {
            int maximum = Math.max(0, size.x - layout.width);
            layout.x = offset < 0 ? maximum / 2 : clamp(offset, 0, maximum);
            layout.y = 0;
        } else {
            int maximum = Math.max(0, size.y - layout.height);
            layout.y = offset < 0 ? maximum / 2 : clamp(offset, 0, maximum);
            layout.x = side == BackButtonService.SIDE_RIGHT
                    ? Math.max(0, size.x - layout.width) : 0;
        }
        updateWindow();
    }

    @SuppressWarnings("deprecation")
    private Point displaySize() {
        Point size = new Point();
        windowManager.getDefaultDisplay().getSize(size);
        return size;
    }

    private void updateWindow() {
        try {
            windowManager.updateViewLayout(buttons, layout);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot position navigation overlay", error);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        private int selectedAction;
        private int startX;
        private int startY;
        private float downX;
        private float downY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    boolean horizontal = preferences.getInt("floatingBackSide", BackButtonService.SIDE_LEFT)
                            == BackButtonService.SIDE_TOP;
                    boolean home = horizontal
                            ? event.getX() >= view.getWidth() / 2f
                            : event.getY() >= view.getHeight() / 2f;
                    selectedAction = home ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK;
                    startX = layout.x;
                    startY = layout.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int deltaX = (int) (event.getRawX() - downX);
                    int deltaY = (int) (event.getRawY() - downY);
                    if (!dragging && Math.hypot(deltaX, deltaY) > slop) dragging = true;
                    if (dragging) {
                        Point size = displaySize();
                        if (preferences.getInt("floatingBackSide", BackButtonService.SIDE_LEFT)
                                == BackButtonService.SIDE_TOP) {
                            layout.x = clamp(startX + deltaX, 0, Math.max(0, size.x - layout.width));
                        } else {
                            layout.y = clamp(startY + deltaY, 0, Math.max(0, size.y - layout.height));
                        }
                        updateWindow();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int offset = preferences.getInt("floatingBackSide", BackButtonService.SIDE_LEFT)
                                == BackButtonService.SIDE_TOP ? layout.x : layout.y;
                        // Persist before the process can be stopped by physical sleep.
                        preferences.edit().putInt("floatingBackOffset", offset).commit();
                    } else {
                        action.accept(selectedAction);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    updatePosition();
                    return true;
                default:
                    return false;
            }
        }
    }
}
