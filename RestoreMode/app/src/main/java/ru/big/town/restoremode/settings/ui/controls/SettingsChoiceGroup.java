package ru.big.town.restoremode.settings.ui.controls;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioGroup;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

/** CSS-like flex choices, retaining direct RadioButton children and native mutual exclusion. */
public final class SettingsChoiceGroup extends RadioGroup {
    private final boolean stack;
    private final List<View> visible = new ArrayList<>();
    private final List<Rect> bounds = new ArrayList<>();

    public SettingsChoiceGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        android.content.res.TypedArray a =
                context.obtainStyledAttributes(attrs, R.styleable.SettingsChoiceGroup);
        stack = a.getBoolean(R.styleable.SettingsChoiceGroup_settingsStack, false);
        a.recycle();
    }

    public boolean isStack() {
        return stack;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), gap = SettingsDesign.dp(this, 8);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        visible.clear();
        bounds.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            c.measure(
                    MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            visible.add(c);
        }
        int y = getPaddingTop(), start = 0;
        while (start < visible.size()) {
            int end = start, used = 0;
            while (end < visible.size()) {
                int next = visible.get(end).getMeasuredWidth() + (end == start ? 0 : gap);
                if (end > start && (stack || used + next > available)) {
                    break;
                }
                used += next;
                end++;
            }
            int extra = Math.max(0, available - used), x = getPaddingLeft(), rowHeight = 0;
            for (int i = start; i < end; i++) {
                View c = visible.get(i);
                int cw =
                        stack
                                ? available
                                : c.getMeasuredWidth()
                                        + extra / (end - start)
                                        + (i == end - 1 ? extra % (end - start) : 0);
                c.measure(
                        MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                bounds.add(new Rect(x, y, x + cw, y + c.getMeasuredHeight()));
                x += cw + gap;
                rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
            }
            y += rowHeight + gap;
            start = end;
        }
        if (!visible.isEmpty()) {
            y -= gap;
        }
        setMeasuredDimension(
                resolveSize(width, widthSpec), resolveSize(y + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < visible.size(); i++) {
            Rect box = bounds.get(i);
            int x =
                    getLayoutDirection() == LAYOUT_DIRECTION_RTL
                            ? getWidth() - box.right
                            : box.left;
            visible.get(i).layout(x, box.top, x + box.width(), box.bottom);
        }
    }
}
