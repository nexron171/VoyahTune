package ru.big.town.restoremode.widgets.brightness;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.big.town.common.BrightnessWidgetProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.appearance.AppearanceSettings;

import java.util.function.IntConsumer;

/** Display modes use the same button surfaces and spacing as the headlight widget. */
public final class BrightnessWidgetView extends FrameLayout {
    private static final int[] MODES = {
        BrightnessWidgetProtocol.DAY,
        BrightnessWidgetProtocol.NIGHT,
        BrightnessWidgetProtocol.AUTO
    };
    private static final String[] LABELS = {"День", "Ночь", "Авто"};

    private final TextView heading;
    private final LinearLayout row;
    private final TextView[] buttons = new TextView[MODES.length];
    private int surfaceAlpha;

    public BrightnessWidgetView(Context context, IntConsumer selection) {
        super(context);
        surfaceAlpha = AppearanceSettings.load(
                context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE))
                .surfaceAlpha();
        setBackgroundResource(R.drawable.card_dark_ripple);

        heading = new TextView(context);
        heading.setTextColor(0xffeeeeee);
        heading.setTextSize(15);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setIncludeFontPadding(false);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        addView(heading);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row);
        for (int index = 0; index < buttons.length; index++) {
            int mode = MODES[index];
            TextView button = new TextView(context);
            button.setText(LABELS[index]);
            button.setTextSize(18);
            button.setTextColor(0xffffffff);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setIncludeFontPadding(false);
            button.setSingleLine(true);
            button.setGravity(Gravity.CENTER);
            button.setFocusable(true);
            button.setTooltipText(LABELS[index]);
            button.setOnClickListener(view -> selection.accept(mode));
            button.setOnLongClickListener(view -> performLongClick());

            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1);
            if (index > 0) layout.leftMargin = dp(6);
            row.addView(button, layout);
            buttons[index] = button;
        }
        update(new Bundle());
    }

    public void update(Bundle state) {
        int selected = state.getInt(BrightnessWidgetProtocol.MODE, -1);
        int pending = state.getInt(BrightnessWidgetProtocol.PENDING, -1);
        boolean available = state.getBoolean(BrightnessWidgetProtocol.AVAILABLE, false);
        String message = state.getString(BrightnessWidgetProtocol.MESSAGE, "Ожидание Native");
        heading.setText(message.isEmpty() ? "Яркость дисплеев" : "Яркость дисплеев · " + message);
        heading.setContentDescription(heading.getText());

        for (int index = 0; index < buttons.length; index++) {
            int mode = MODES[index];
            boolean active = selected == mode;
            TextView button = buttons[index];
            button.setSelected(active);
            button.setActivated(pending == mode);
            button.setEnabled(available && pending < 0);
            button.setTextColor(selected < 0 ? 0x73ffffff : 0xffffffff);
            button.setContentDescription(
                    "Яркость дисплеев: " + LABELS[index]
                            + (active ? ", выбран" : "")
                            + (pending == mode ? ", ожидается" : ""));

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(8));
            background.setColor(active ? 0xff2e80c9 : 0xff354154);
            if (pending == mode) background.setStroke(dp(2), 0xff91c8f5);
            background.setAlpha(surfaceAlpha);
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x40ffffff), background, null));
        }
    }

    public void setSurfaceAlpha(int alpha) {
        surfaceAlpha = alpha;
        for (TextView button : buttons) {
            if (button.getBackground() instanceof RippleDrawable) {
                ((RippleDrawable) button.getBackground()).getDrawable(0).setAlpha(alpha);
            }
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int contentWidth = Math.max(0, width - dp(20));
        int headingHeight = Math.min(dp(20), Math.max(0, height - dp(20)));
        int buttonHeight = Math.max(0, height - dp(20) - headingHeight - dp(6));
        heading.measure(exact(contentWidth), exact(headingHeight));
        row.measure(exact(contentWidth), exact(buttonHeight));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int padding = dp(10);
        heading.layout(
                padding, padding,
                padding + heading.getMeasuredWidth(), padding + heading.getMeasuredHeight());
        int buttonTop = padding + heading.getMeasuredHeight() + dp(6);
        row.layout(
                padding, buttonTop,
                padding + row.getMeasuredWidth(), buttonTop + row.getMeasuredHeight());
    }

    private static int exact(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
