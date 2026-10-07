package ru.big.town.restoremode.settings.ui.layout;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

/** Content-sized chips wrapping with the same 12dp gap as the HTML. */
public final class SettingsFlow extends LinearLayout {
    private final List<View> visible = new ArrayList<>();
    private final List<Rect> bounds = new ArrayList<>();

    public SettingsFlow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w),
                gap = SettingsDesign.dp(this, 12),
                x = 0,
                y = 0,
                row = 0;
        visible.clear();
        bounds.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
            child.measure(
                    MeasureSpec.makeMeasureSpec(
                            lp.width > 0 ? lp.width : width,
                            lp.width > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(
                            lp.height > 0 ? lp.height : 0,
                            lp.height > 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
            int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
            if (x > 0 && x + cw > width) {
                x = 0;
                y += row + gap;
                row = 0;
            }
            visible.add(child);
            bounds.add(new Rect(x, y, x + cw, y + ch));
            x += cw + gap;
            row = Math.max(row, ch);
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(y + row, h));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < visible.size(); i++) {
            Rect box = bounds.get(i);
            visible.get(i).layout(box.left, box.top, box.right, box.bottom);
        }
    }
}
