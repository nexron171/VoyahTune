package ru.big.town.restoremode.settings.ui.layout;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

/** Equal columns and stretched rows, with a narrow-window fallback. */
public final class SettingsGrid extends LinearLayout {
    private final int maxColumns;
    private final int minimumColumnWidth;
    private final boolean lastFull;
    private final float endColumnWeight;
    private final List<View> visible = new ArrayList<>();
    private final List<Rect> bounds = new ArrayList<>();

    public SettingsGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        android.content.res.TypedArray attributes =
                context.obtainStyledAttributes(attrs, R.styleable.SettingsGrid);
        maxColumns =
                Math.max(1, Math.min(5, attributes.getInt(R.styleable.SettingsGrid_settingsColumns, 2)));
        minimumColumnWidth = Math.max(1, attributes.getDimensionPixelSize(
                R.styleable.SettingsGrid_settingsMinimumColumnWidth, SettingsDesign.dp(this, 360)));
        lastFull = attributes.getBoolean(R.styleable.SettingsGrid_settingsLastFull, false);
        endColumnWeight =
                Math.max(1f, attributes.getFloat(R.styleable.SettingsGrid_settingsEndColumnWeight, 1f));
        attributes.recycle();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int gap = SettingsDesign.dp(this, 16);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int columns =
                Math.min(
                        maxColumns,
                        Math.max(1, (available + gap) / (minimumColumnWidth + gap)));
        int rowWidth = Math.max(0, available - gap * (columns - 1));
        float cellWidth =
                Math.max(
                        0,
                        rowWidth / (columns - 1 + (columns == maxColumns ? endColumnWeight : 1f)));
        visible.clear();
        bounds.clear();
        for (int index = 0; index < getChildCount(); index++) {
            if (getChildAt(index).getVisibility() != GONE) {
                visible.add(getChildAt(index));
            }
        }
        int y = getPaddingTop();
        for (int start = 0; start < visible.size(); start += columns) {
            int end = Math.min(start + columns, visible.size());
            int rowHeight = 0;
            boolean full = lastFull && end - start == 1 && end == visible.size();
            for (int index = start; index < end; index++) {
                View child = visible.get(index);
                int left = Math.round((index - start) * (cellWidth + gap));
                int childWidth =
                        full
                                ? available
                                : index - start == columns - 1
                                        ? available - left
                                        : Math.round((index - start + 1) * (cellWidth + gap))
                                                - gap
                                                - left;
                child.measure(
                        MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            for (int index = start; index < end; index++) {
                View child = visible.get(index);
                int left = Math.round((index - start) * (cellWidth + gap));
                int childWidth =
                        full
                                ? available
                                : index - start == columns - 1
                                        ? available - left
                                        : Math.round((index - start + 1) * (cellWidth + gap))
                                                - gap
                                                - left;
                child.measure(
                        MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                int x = getPaddingLeft() + left;
                bounds.add(new Rect(x, y, x + childWidth, y + rowHeight));
            }
            y += rowHeight + gap;
        }
        if (!visible.isEmpty()) {
            y -= gap;
        }
        setMeasuredDimension(
                resolveSize(width, widthSpec), resolveSize(y + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int index = 0; index < visible.size(); index++) {
            Rect box = bounds.get(index);
            int x =
                    getLayoutDirection() == LAYOUT_DIRECTION_RTL
                            ? getWidth() - box.right
                            : box.left;
            visible.get(index).layout(x, box.top, x + box.width(), box.bottom);
        }
    }
}
