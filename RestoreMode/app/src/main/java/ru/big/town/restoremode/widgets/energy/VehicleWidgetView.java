package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.restoremode.R;

import java.util.Locale;

/** Shared drawing surface and telemetry access for vehicle cards. */
public abstract class VehicleWidgetView extends View {
    protected static final int WHITE = EnergyWidgetStyle.WHITE;
    protected static final int MUTED = EnergyWidgetStyle.MUTED;
    protected static final int GREEN = EnergyWidgetStyle.GREEN;
    protected static final int BLUE = EnergyWidgetStyle.BLUE;
    protected static final int BORDER = EnergyWidgetStyle.BORDER;
    protected static final Locale RU = new Locale("ru", "RU");
    protected final int columns;
    protected final int rows;
    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    protected Bundle state = new Bundle();
    protected float contentWidth;
    protected float contentHeight;
    private final float designWidth;
    private final float designHeight;
    private float touchX;
    private float touchY;
    private float scale = 1;
    private float offsetX;
    private float offsetY;

    protected VehicleWidgetView(Context context, String widgetId, int columns, int rows) {
        super(context);
        this.columns = EnergyWidgetLayout.width(widgetId, columns);
        this.rows = EnergyWidgetLayout.height(widgetId, this.columns, rows);
        designWidth = EnergyWidgetLayout.pixelsWide(this.columns);
        designHeight = EnergyWidgetLayout.pixelsHigh(widgetId, this.rows);
        contentWidth = designWidth;
        contentHeight = designHeight;
        setFocusable(true);
        setClickable(true);
        setOnClickListener(view -> onTap((touchX - offsetX) / scale, (touchY - offsetY) / scale));
    }

    public final void update(Bundle data) {
        Bundle previous = state;
        state = data == null ? new Bundle() : new Bundle(data);
        onStateChanged(previous);
        setContentDescription(description());
        invalidate();
    }

    protected void onStateChanged(Bundle previous) {}

    protected void onTap(float x, float y) {}

    protected boolean usesResponsiveLayout() {
        return false;
    }

    protected float footerHeight() {
        return 0;
    }

    protected abstract String description();

    protected abstract void drawWidget(Canvas canvas);

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        touchX = event.getX();
        touchY = event.getY();
        return super.onTouchEvent(event);
    }

    @Override
    protected final void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(0xff22252f);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 20, 20, paint);
        paint.setColor(BORDER);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        canvas.drawRoundRect(.5f, .5f, getWidth() - .5f, getHeight() - .5f, 20, 20, paint);
        paint.setStyle(Paint.Style.FILL);
        float availableHeight = Math.max(0, getHeight() - footerHeight());
        if (usesResponsiveLayout()) {
            scale = getResources().getDisplayMetrics().density;
            contentWidth = getWidth() / scale;
            contentHeight = availableHeight / scale;
            offsetX = offsetY = 0;
        } else {
            contentWidth = designWidth;
            contentHeight = designHeight;
            scale = Math.min(getWidth() / contentWidth, availableHeight / contentHeight);
            offsetX = (getWidth() - contentWidth * scale) / 2;
            offsetY = (availableHeight - contentHeight * scale) / 2;
        }
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawWidget(canvas);
        canvas.restore();
    }

    protected final boolean hasLiveData() {
        long time = state.getLong(EnergyWidgetProtocol.UPDATED, -1),
                now = SystemClock.elapsedRealtime();
        return state.getInt(EnergyWidgetProtocol.SCHEMA) == EnergyWidgetProtocol.VERSION
                && state.getBoolean(EnergyWidgetProtocol.CONNECTED)
                && time >= 0
                && now >= time
                && now - time <= EnergyWidgetProtocol.UI_TIMEOUT_MS;
    }

    protected final float[] floatArray(String key, int size) {
        float[] values = state.getFloatArray(key);
        return values != null && values.length >= size ? values : new float[0];
    }

    protected final float currentValue(String key, int index) {
        float[] values = floatArray(key, index + 1);
        return hasLiveData() && values.length > index ? values[index] : Float.NaN;
    }

    protected final double recordedKm() {
        float[] x = floatArray(EnergyWidgetProtocol.HISTORY_X, 0);
        double fallback = x.length == 0 ? 0 : x[x.length - 1];
        double km = state.getDouble(EnergyWidgetProtocol.RECORDED_KM, fallback);
        return Double.isFinite(km) && km >= 0 ? km : fallback;
    }

    protected static String formatNumber(float value) {
        return Float.isFinite(value) ? String.format(RU, "%.1f", value) : "—";
    }

    protected static String formatMileage(double value) {
        return Double.isFinite(value) ? String.format(RU, "%,.0f", value) : "—";
    }

    protected final void drawText(
            Canvas canvas, String text, float x, float y, float size, int color, boolean bold) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(
                bold
                        ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        : Typeface.create("sans-serif", Typeface.NORMAL));
        canvas.drawText(text, x, y, paint);
    }

    protected final void drawRightAlignedText(
            Canvas canvas, String text, float x, float y, float size, int color) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        drawText(canvas, text, x - paint.measureText(text), y, size, color, false);
    }

    protected final void drawLine(
            Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    protected final float measureText(String value, float size) {
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(size);
        return paint.measureText(value);
    }

    protected final float fontSize(int resource) {
        return getResources().getDimension(resource) / scale;
    }

    protected final float valueFont() {
        return fontSize(R.dimen.energy_value_text_size);
    }

    protected final float labelFont() {
        return fontSize(R.dimen.energy_label_text_size);
    }

    protected final float unitFont() {
        return fontSize(R.dimen.energy_unit_text_size);
    }

    protected final float captionFont() {
        return fontSize(R.dimen.energy_caption_text_size);
    }

    protected final float valueWithUnit(
            Canvas canvas,
            String value,
            String unit,
            float x,
            float y,
            float size,
            float unitSize,
            int color) {
        drawText(canvas, value, x, y, size, color, false);
        float next = x + measureText(value, size) + 6;
        drawText(canvas, unit, next, y, unitSize, color, false);
        return next + measureText(unit, unitSize);
    }

    protected static String formatEstimate(float value) {
        return EnergyWidgetStyle.estimate(value);
    }

    protected final float drawWrappedText(
            Canvas canvas,
            String value,
            float x,
            float baseline,
            float size,
            float width,
            int color) {
        String lineText = "";
        for (String word : value.split(" ")) {
            String next = lineText.isEmpty() ? word : lineText + " " + word;
            if (!lineText.isEmpty() && measureText(next, size) > width) {
                drawText(canvas, lineText, x, baseline, size, color, false);
                baseline += size * 1.25f;
                lineText = word;
            } else {
                lineText = next;
            }
        }
        if (!lineText.isEmpty()) {
            drawText(canvas, lineText, x, baseline, size, color, false);
        }
        return baseline;
    }
}
