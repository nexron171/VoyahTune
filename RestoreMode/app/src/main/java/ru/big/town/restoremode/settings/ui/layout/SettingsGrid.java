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
    private final boolean lastFull;
    private final float endColumnWeight;
    private final List<View> visible = new ArrayList<>();
    private final List<Rect> bounds = new ArrayList<>();

    public SettingsGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        android.content.res.TypedArray a =
                context.obtainStyledAttributes(attrs, R.styleable.SettingsGrid);
        maxColumns =
                Math.max(1, Math.min(3, a.getInt(R.styleable.SettingsGrid_settingsColumns, 2)));
        lastFull = a.getBoolean(R.styleable.SettingsGrid_settingsLastFull, false);
        endColumnWeight =
                Math.max(1f, a.getFloat(R.styleable.SettingsGrid_settingsEndColumnWeight, 1f));
        a.recycle();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), gap = SettingsDesign.dp(this, 16);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int columns =
                Math.min(
                        maxColumns,
                        Math.max(1, (available + gap) / (SettingsDesign.dp(this, 360) + gap)));
        int rowWidth = Math.max(0, available - gap * (columns - 1));
        float cellWidth =
                Math.max(
                        0,
                        rowWidth / (columns - 1 + (columns == maxColumns ? endColumnWeight : 1f)));
        visible.clear();
        bounds.clear();
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != GONE) {
                visible.add(getChildAt(i));
            }
        }
        int y = getPaddingTop();
        for (int start = 0; start < visible.size(); start += columns) {
            int end = Math.min(start + columns, visible.size()), rowHeight = 0;
            boolean full = lastFull && end - start == 1 && end == visible.size();
            for (int i = start; i < end; i++) {
                View c = visible.get(i);
                int left = Math.round((i - start) * (cellWidth + gap));
                int cw =
                        full
                                ? available
                                : i - start == columns - 1
                                        ? available - left
                                        : Math.round((i - start + 1) * (cellWidth + gap))
                                                - gap
                                                - left;
                c.measure(
                        MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
            }
            for (int i = start; i < end; i++) {
                View c = visible.get(i);
                int left = Math.round((i - start) * (cellWidth + gap));
                int cw =
                        full
                                ? available
                                : i - start == columns - 1
                                        ? available - left
                                        : Math.round((i - start + 1) * (cellWidth + gap))
                                                - gap
                                                - left;
                c.measure(
                        MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                int x = getPaddingLeft() + left;
                bounds.add(new Rect(x, y, x + cw, y + rowHeight));
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
