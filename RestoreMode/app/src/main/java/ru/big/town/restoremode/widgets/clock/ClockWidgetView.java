package ru.big.town.restoremode.widgets.clock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextClock;

import ru.big.town.restoremode.R;

/** System time with a fixed 24-hour format and text fitted to the available tile. */
public final class ClockWidgetView extends TextClock {
    public static final String ID = "clockWidget";

    public ClockWidgetView(Context context) {
        super(context);
        setFormat12Hour("HH:mm");
        setFormat24Hour("HH:mm");
        setGravity(Gravity.CENTER);
        setTextColor(Color.WHITE);
        setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        setFontFeatureSettings("tnum");
        setIncludeFontPadding(false);
        setMaxLines(1);
        setHorizontallyScrolling(false);
        setBackgroundResource(R.drawable.layout_category_bg);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setFocusable(true);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int availableHeight = height - getPaddingTop() - getPaddingBottom();
        if (availableHeight > 1) {
            setAutoSizeTextTypeUniformWithConfiguration(
                    1, availableHeight, 1, TypedValue.COMPLEX_UNIT_PX);
        }
    }
}
