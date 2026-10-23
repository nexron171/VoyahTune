package ru.big.town.restoremode.widgets.headlights;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.common.HeadlightWidgetProtocol;
import ru.big.town.restoremode.R;

import java.util.function.IntConsumer;

/** Four accessible buttons remain directly available in every supported tile size. */
public final class HeadlightWidgetView extends FrameLayout {
    private static final String[] LABELS = {"Выкл", "Габариты", "Ближний", "Авто"};
    private final TextView heading;
    private final LinearLayout[] buttons = new LinearLayout[4];
    private final View[] symbols = new View[4];
    private final int columns;

    public HeadlightWidgetView(Context context, int width, IntConsumer selection) {
        super(context);
        int tileWidth = HeadlightWidgetLayout.width(width);
        columns = tileWidth <= 2 ? tileWidth : 4;
        setBackgroundResource(R.drawable.card_dark_ripple);
        heading = new TextView(context);
        heading.setTextSize(15);
        heading.setTextColor(0xffeeeeee);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setIncludeFontPadding(false);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        addView(heading);

        for (int mode = 0; mode < buttons.length; mode++) {
            int selectedMode = mode;
            LinearLayout button = new LinearLayout(context);
            button.setOrientation(LinearLayout.VERTICAL);
            button.setGravity(Gravity.CENTER);
            button.setFocusable(true);
            button.setContentDescription("Фары: " + LABELS[mode]);
            button.setTooltipText(LABELS[mode]);
            button.setOnClickListener(view -> selection.accept(selectedMode));
            button.setOnLongClickListener(view -> performLongClick());

            View symbol;
            if (mode == HeadlightWidgetProtocol.OFF || mode == HeadlightWidgetProtocol.AUTO) {
                TextView text = new TextView(context);
                text.setText(mode == HeadlightWidgetProtocol.OFF ? "Off" : "A");
                text.setTextColor(0xffffffff);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                text.setIncludeFontPadding(false);
                text.setMaxLines(1);
                text.setGravity(Gravity.CENTER);
                symbol = text;
            } else {
                ImageView icon = new ImageView(context);
                icon.setImageResource(
                        mode == HeadlightWidgetProtocol.PARKING
                                ? R.drawable.headlight_parking
                                : R.drawable.headlight_low_beam);
                symbol = icon;
            }
            symbol.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            button.addView(symbol, new LinearLayout.LayoutParams(dp(36), dp(36)));
            buttons[mode] = button;
            symbols[mode] = symbol;
            addView(button);
        }
        update(new Bundle());
    }

    public void update(Bundle state) {
        int selected = state.getInt(HeadlightWidgetProtocol.MODE, -1);
        int pending = state.getInt(HeadlightWidgetProtocol.PENDING, -1);
        boolean available = state.getBoolean(HeadlightWidgetProtocol.AVAILABLE, false);
        String message = state.getString(HeadlightWidgetProtocol.MESSAGE, "Ожидание Native");
        heading.setText(message.isEmpty() ? "Фары" : "Фары · " + message);
        heading.setContentDescription(heading.getText());
        for (int mode = 0; mode < buttons.length; mode++) {
            boolean active = mode == selected;
            LinearLayout button = buttons[mode];
            button.setEnabled(available && pending < 0);
            button.setSelected(active);
            button.setActivated(mode == pending);
            button.setContentDescription(
                    "Фары: "
                            + LABELS[mode]
                            + (active ? ", выбран" : "")
                            + (mode == pending ? ", ожидается" : ""));
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(8));
            background.setColor(active ? 0xff2e80c9 : 0xff354154);
            if (mode == pending) {
                background.setStroke(dp(2), 0xff91c8f5);
            }
            button.setBackground(
                    new RippleDrawable(ColorStateList.valueOf(0x40ffffff), background, null));
            symbols[mode].setAlpha(selected < 0 ? 0.45f : 1f);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int padding = dp(10);
        int gap = dp(6);
        int headingHeight = dp(20);
        int rows = 4 / columns;
        int buttonWidth = Math.max(0, (width - padding * 2 - gap * (columns - 1)) / columns);
        int buttonHeight = Math.max(0, (height - padding * 2 - headingHeight - gap * rows) / rows);
        heading.measure(exact(Math.max(0, width - padding * 2)), exact(headingHeight));
        for (int mode = 0; mode < buttons.length; mode++) {
            int iconSize =
                    Math.min(
                            dp(36),
                            Math.max(0, Math.min(buttonWidth - dp(12), buttonHeight - dp(10))));
            symbols[mode].getLayoutParams().width = iconSize;
            symbols[mode].getLayoutParams().height = iconSize;
            if (symbols[mode] instanceof TextView) {
                ((TextView) symbols[mode])
                        .setTextSize(
                                android.util.TypedValue.COMPLEX_UNIT_PX,
                                iconSize * (mode == HeadlightWidgetProtocol.OFF ? 0.61f : 0.83f));
            }
            buttons[mode].measure(exact(buttonWidth), exact(buttonHeight));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int padding = dp(10);
        int gap = dp(6);
        heading.layout(
                padding, padding, getWidth() - padding, padding + heading.getMeasuredHeight());
        for (int mode = 0; mode < buttons.length; mode++) {
            View button = buttons[mode];
            int buttonLeft = padding + (mode % columns) * (button.getMeasuredWidth() + gap);
            int buttonTop =
                    padding
                            + heading.getMeasuredHeight()
                            + gap
                            + (mode / columns) * (button.getMeasuredHeight() + gap);
            button.layout(
                    buttonLeft,
                    buttonTop,
                    buttonLeft + button.getMeasuredWidth(),
                    buttonTop + button.getMeasuredHeight());
        }
    }

    private static int exact(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
