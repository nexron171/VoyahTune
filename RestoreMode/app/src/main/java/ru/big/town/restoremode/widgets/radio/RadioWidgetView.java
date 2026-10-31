package ru.big.town.restoremode.widgets.radio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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

import ru.big.town.common.RadioWidgetProtocol;
import ru.big.town.restoremode.R;

import java.util.function.Consumer;

public final class RadioWidgetView extends FrameLayout {
    private static final int TEXT = 0xffeef1f6;
    private static final int MUTED = 0xffaab3c4;
    private final TextView band;
    private final TextView station;
    private final TextView frequency;
    private final TextView unit;
    private final TextView status;
    private final LinearLayout previous;
    private final LinearLayout playback;
    private final LinearLayout next;
    private final ControlIcon playbackIcon;
    private final TextView playbackLabel;
    private final ImageView background;
    private boolean playing;

    public RadioWidgetView(
            Context context, int columns, int rows, Runnable open, Consumer<String> command) {
        super(context);
        boolean compact = RadioWidgetLayout.height(rows) == 2;
        int padding = dp(columns == 3 ? 22 : 18);
        setClipToOutline(true);
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(dp(20));
        outline.setColor(0xff22252f);
        setBackground(outline);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(view -> open.run());

        background = new ImageView(context);
        background.setImageResource(R.drawable.radio_widget_background);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dp(18), padding, dp(18));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Радио", 24, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(29), 1));
        band = text("FM", 18, MUTED);
        heading.addView(band);
        content.addView(heading);

        LinearLayout information = new LinearLayout(context);
        information.setOrientation(LinearLayout.VERTICAL);
        information.setGravity(Gravity.CENTER);
        information.setPadding(0, dp(10), 0, dp(10));
        content.addView(
                information, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        station = text("", 26, TEXT);
        station.setGravity(Gravity.CENTER);
        station.setMaxLines(compact ? 1 : 2);
        station.setEllipsize(TextUtils.TruncateAt.END);
        station.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        information.addView(
                station,
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout frequencyLine = new LinearLayout(context);
        frequencyLine.setGravity(Gravity.CENTER);
        frequencyLine.setBaselineAligned(true);
        frequency = text("—", 44, TEXT);
        frequency.setSingleLine(true);
        frequencyLine.addView(frequency);
        unit = text("МГц", 20, MUTED);
        LinearLayout.LayoutParams unitLayout =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        unitLayout.leftMargin = dp(9);
        frequencyLine.addView(unit, unitLayout);
        information.addView(frequencyLine);
        status = text("Ожидание радио", 18, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setVisibility(compact ? GONE : VISIBLE);
        LinearLayout.LayoutParams statusLayout =
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusLayout.topMargin = dp(17);
        information.addView(status, statusLayout);

        LinearLayout controls = new LinearLayout(context);
        content.addView(
                controls,
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(compact ? 56 : 78)));
        previous = control(controls, "Назад", "Предыдущая станция", -1, false, compact);
        playback = control(controls, "Играть", "Включить радио", 0, true, compact);
        next = control(controls, "Вперёд", "Следующая станция", 1, false, compact);
        playbackIcon = (ControlIcon) playback.getChildAt(0);
        playbackLabel = (TextView) playback.getChildAt(1);
        previous.setOnClickListener(view -> command.accept(RadioWidgetProtocol.PREVIOUS));
        next.setOnClickListener(view -> command.accept(RadioWidgetProtocol.NEXT));
        playback.setOnClickListener(
                view ->
                        command.accept(
                                playing ? RadioWidgetProtocol.PAUSE : RadioWidgetProtocol.PLAY));

        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(20));
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(1), 0xff373f4a);
        setForeground(border);
        update(new Bundle());
    }

    public void setCustomBackground(boolean custom) {
        background.setVisibility(custom ? GONE : VISIBLE);
        setForeground(null);
    }

    public void update(Bundle state) {
        boolean fm = state.getBoolean(RadioWidgetProtocol.FM, true);
        int rawFrequency = state.getInt(RadioWidgetProtocol.FREQUENCY, -1);
        boolean valid = RadioWidgetProtocol.validFrequency(fm, rawFrequency);
        boolean ready = state.getBoolean(RadioWidgetProtocol.READY);
        playing = state.getBoolean(RadioWidgetProtocol.PLAYING);
        String name = state.getString(RadioWidgetProtocol.NAME, "");
        station.setText(name);
        station.setVisibility(valid && !name.isEmpty() ? VISIBLE : GONE);
        station.setTooltipText(name.isEmpty() ? null : name);
        frequency.setText(RadioWidgetProtocol.frequency(fm, rawFrequency));
        unit.setText(fm ? "МГц" : "кГц");
        unit.setVisibility(valid ? VISIBLE : GONE);
        band.setText(fm ? "FM" : "AM");
        String message = state.getString(RadioWidgetProtocol.MESSAGE, "");
        if (message.isEmpty()) {
            message = ready ? (playing ? "В эфире" : "На паузе") : "Ожидание радио";
        }
        status.setText(message);
        playbackIcon.playing = playing;
        playbackIcon.invalidate();
        playbackLabel.setText(playing ? "Пауза" : "Играть");
        playback.setContentDescription(playing ? "Пауза" : "Включить выбранную станцию");
        enable(playback, ready && (valid || playing));
        enable(previous, ready && valid && state.getBoolean(RadioWidgetProtocol.CAN_SKIP));
        enable(next, ready && valid && state.getBoolean(RadioWidgetProtocol.CAN_SKIP));
        setContentDescription(
                "Открыть радио. "
                        + name
                        + " "
                        + frequency.getText()
                        + (valid ? " " + unit.getText() : "")
                        + ". "
                        + message);
    }

    private void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : .45f);
    }

    private LinearLayout control(
            LinearLayout parent,
            String label,
            String description,
            int direction,
            boolean primary,
            boolean compact) {
        LinearLayout button = new LinearLayout(getContext());
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setContentDescription(description);
        button.setOnLongClickListener(view -> performLongClick());
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(14));
        fill.setColor(primary ? 0xff2e80c9 : 0xff373f4a);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), fill, null));
        ControlIcon icon = new ControlIcon(getContext(), direction);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView caption = text(label, 18, TEXT);
        caption.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        caption.setVisibility(compact ? GONE : VISIBLE);
        LinearLayout.LayoutParams captionLayout =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        captionLayout.topMargin = dp(5);
        button.addView(caption, captionLayout);
        LinearLayout.LayoutParams layout =
                new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, primary ? 1.12f : 1);
        if (parent.getChildCount() > 0) layout.leftMargin = dp(8);
        parent.addView(button, layout);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ControlIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private final int direction;
        private boolean playing;

        ControlIcon(Context context, int direction) {
            super(context);
            this.direction = direction;
            paint.setColor(TEXT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 32f, getHeight() / 32f);
            if (direction == 0 && playing) {
                canvas.drawRect(8, 5, 14, 27, paint);
                canvas.drawRect(18, 5, 24, 27, paint);
            } else {
                triangle.reset();
                triangle.moveTo(direction < 0 ? 27 : 9, 5);
                triangle.lineTo(direction < 0 ? 10 : 26, 16);
                triangle.lineTo(direction < 0 ? 27 : 9, 27);
                triangle.close();
                canvas.drawPath(triangle, paint);
                if (direction != 0) {
                    int left = direction < 0 ? 5 : 27;
                    canvas.drawRect(left, 5, left + 3, 27, paint);
                }
            }
            canvas.restore();
        }
    }
}
