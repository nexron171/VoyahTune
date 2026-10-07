package ru.big.town.restoremode.settings.ui.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.NumberPicker;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/**
 * Compact value field with a native bounded picker; preserves NumberPicker listeners and limits.
 */
public final class SettingsNumberPicker extends NumberPicker {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnValueChangeListener listener;
    private float downX, downY;
    private boolean dragging;

    public SettingsNumberPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(GONE);
        }
        setBackgroundResource(R.drawable.settings_field);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    public void setOnValueChangedListener(OnValueChangeListener listener) {
        this.listener = listener;
        super.setOnValueChangedListener(listener);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(
                resolveSize(SettingsDesign.dp(this, 115), w),
                resolveSize(SettingsDesign.dp(this, 52), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setColor(isEnabled() ? 0xffe6efff : 0xff718198);
        paint.setTextSize(22 * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(getResources().getFont(R.font.settings_arimo_regular));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(
                String.valueOf(getValue()),
                getWidth() / 2f,
                (getHeight() - paint.ascent() - paint.descent()) / 2f,
                paint);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return null;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(
            android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setText(String.valueOf(getValue()));
        info.setClickable(isEnabled());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            dragging = false;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            dragging |=
                    Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop;
        }
        if (event.getAction() == MotionEvent.ACTION_UP && !dragging) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (!isEnabled()) {
            return false;
        }
        NumberPicker picker = new NumberPicker(getContext());
        picker.setMinValue(getMinValue());
        picker.setMaxValue(getMaxValue());
        picker.setValue(getValue());
        picker.setWrapSelectorWheel(false);
        android.widget.LinearLayout content = new android.widget.LinearLayout(getContext());
        content.setGravity(android.view.Gravity.CENTER);
        content.addView(
                picker,
                new android.widget.LinearLayout.LayoutParams(
                        SettingsDesign.dp(this, 140), SettingsDesign.dp(this, 180)));
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                getContext(), R.style.SettingsDialog)
                        .setTitle(getContentDescription())
                        .setView(content)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton(
                                "Выбрать",
                                (sheet, which) -> {
                                    int old = getValue();
                                    picker.clearFocus();
                                    setValue(picker.getValue());
                                    invalidate();
                                    if (old != getValue() && listener != null) {
                                        listener.onValueChange(this, old, getValue());
                                    }
                                })
                        .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow()
                    .setLayout(
                            SettingsDesign.dp(this, 620),
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return true;
    }
}
