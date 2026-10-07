package ru.big.town.restoremode.settings.ui.layout;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Settings rows share dividers and collapse outer padding as in the approved layout. */
public final class SettingsRow extends LinearLayout {
    private final Paint paint = new Paint();
    private boolean last;

    public SettingsRow(Context context) {
        this(context, null);
    }

    public SettingsRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    @Override
    protected void onMeasure(int width, int height) {
        ViewGroup parent = (ViewGroup) getParent();
        int index = parent == null ? 0 : parent.indexOfChild(this);
        boolean first = true;
        last = true;
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i).getVisibility() == GONE) {
                    continue;
                }
                if (i < index) {
                    first = false;
                }
                if (i > index) {
                    last = false;
                }
            }
        }
        setPadding(
                0,
                first ? 0 : SettingsDesign.dp(this, 18),
                0,
                last ? 0 : SettingsDesign.dp(this, 19));
        super.onMeasure(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!last) {
            paint.setColor(0xff344052);
            canvas.drawRect(
                    0, getHeight() - SettingsDesign.dp(this, 1), getWidth(), getHeight(), paint);
        }
    }
}
