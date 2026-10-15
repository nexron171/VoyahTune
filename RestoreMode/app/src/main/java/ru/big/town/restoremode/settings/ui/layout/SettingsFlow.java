package ru.big.town.restoremode.settings.ui.layout;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

/** Content-sized chips with wrapping and a configurable gap, defaulting to 12dp. */
public final class SettingsFlow extends LinearLayout {
    private final List<View> visible = new ArrayList<>();
    private final List<Rect> bounds = new ArrayList<>();

    private int gapDp = 12;

    public SettingsFlow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setGapDp(int gapDp) {
        this.gapDp = Math.max(0, gapDp);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int gap = SettingsDesign.dp(this, gapDp);
        int horizontalOffset = 0;
        int verticalOffset = 0;
        int rowHeight = 0;
        visible.clear();
        bounds.clear();
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) {
                continue;
            }
            android.view.ViewGroup.LayoutParams parameters = child.getLayoutParams();
            child.measure(
                    MeasureSpec.makeMeasureSpec(
                            parameters.width > 0 ? parameters.width : width,
                            parameters.width > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(
                            parameters.height > 0 ? parameters.height : 0,
                            parameters.height > 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (horizontalOffset > 0 && horizontalOffset + childWidth > width) {
                horizontalOffset = 0;
                verticalOffset += rowHeight + gap;
                rowHeight = 0;
            }
            visible.add(child);
            bounds.add(new Rect(
                    horizontalOffset, verticalOffset,
                    horizontalOffset + childWidth, verticalOffset + childHeight));
            horizontalOffset += childWidth + gap;
            rowHeight = Math.max(rowHeight, childHeight);
        }
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(verticalOffset + rowHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int index = 0; index < visible.size(); index++) {
            Rect box = bounds.get(index);
            visible.get(index).layout(box.left, box.top, box.right, box.bottom);
        }
    }
}
