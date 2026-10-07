package ru.big.town.restoremode.widgets.system;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetStyle;

import java.util.List;

/** Compact graph in actual dp/sp; the grid owns this View's dimensions. */
final class SystemLoadWidgetView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final SystemLoadHistory history;
    private final boolean cpu;
    private final float density, fontScale;

    SystemLoadWidgetView(Context context, boolean cpu, SystemLoadHistory history) {
        super(context);
        this.cpu = cpu;
        this.history = history;
        density = getResources().getDisplayMetrics().density;
        fontScale = getResources().getDisplayMetrics().scaledDensity / density;
        setBackgroundResource(R.drawable.layout_category_bg);
        setFocusable(true);
    }

    void update() {
        float current = history.current(cpu, SystemClock.elapsedRealtime());
        setContentDescription(
                (cpu ? "CPU" : "RAM")
                        + (Float.isFinite(current)
                                ? ": "
                                        + Math.round(current)
                                        + " процентов. График за последние 2 минуты."
                                : ": нет данных"));
        invalidate();
    }

    private void text(
            Canvas c,
            String value,
            float x,
            float baseline,
            float size,
            int color,
            Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size * fontScale);
        paint.setTextAlign(align);
        c.drawText(value, x, baseline, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(density, density);
        float w = getWidth() / density, h = getHeight() / density;
        int accent = cpu ? EnergyWidgetStyle.GREEN : EnergyWidgetStyle.BLUE;
        long now = SystemClock.elapsedRealtime();
        float value = history.current(cpu, now);
        String title = cpu ? "CPU" : "RAM";
        boolean compact = w < 200;
        float padding = 8;
        float titleSize = compact ? 18 : EnergyWidgetStyle.CHART_TITLE_SP;
        float readingSize = compact ? 20 : 30;
        float unitSize = compact ? 12 : EnergyWidgetStyle.CHART_UNIT_SP;
        float axisSize = compact ? 10 : EnergyWidgetStyle.CHART_AXIS_SP;
        float heading = padding + (compact ? readingSize : titleSize) * fontScale;
        text(canvas, title, padding, heading, titleSize, EnergyWidgetStyle.WHITE, Paint.Align.LEFT);
        paint.setTextSize(titleSize * fontScale);
        float titleWidth = paint.measureText(title);
        String reading = Float.isFinite(value) ? Integer.toString(Math.round(value)) : "—";
        paint.setTextSize(unitSize * fontScale);
        float unitWidth = paint.measureText("%") + 2;
        paint.setTextSize(readingSize * fontScale);
        // Reserve the maximum reading so each sample keeps the graph in place.
        float infoWidth = Math.max(titleWidth, paint.measureText("100") + unitWidth);
        float readingWidth = paint.measureText(reading);
        float axisX, chartTop;
        if (compact) {
            float suffixWidth = Float.isFinite(value) ? unitWidth : 0;
            text(
                    canvas,
                    reading,
                    w - padding - suffixWidth,
                    heading,
                    readingSize,
                    accent,
                    Paint.Align.RIGHT);
            if (Float.isFinite(value)) {
                text(canvas, "%", w - padding, heading, unitSize, accent, Paint.Align.RIGHT);
            }
            axisX = padding;
            chartTop = heading + 2;
        } else {
            float readingBaseline = heading + (readingSize + 6) * fontScale;
            text(canvas, reading, padding, readingBaseline, readingSize, accent, Paint.Align.LEFT);
            if (Float.isFinite(value)) {
                text(
                        canvas,
                        "%",
                        padding + readingWidth + 2,
                        readingBaseline,
                        unitSize,
                        accent,
                        Paint.Align.LEFT);
            }
            axisX = padding + infoWidth + 8;
            chartTop = padding;
        }
        // Keep the last point's radius inside the compact card's 8 dp inset.
        float right = w - padding - (compact ? 2.5f : 0);
        paint.setTextSize(axisSize * fontScale);
        float left = axisX + paint.measureText("100") + (compact ? 2 : 6);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float labelOffset = -(metrics.ascent + metrics.descent) / 2;
        float halfLabelHeight = (metrics.descent - metrics.ascent) / 2;
        float top = chartTop + halfLabelHeight, bottom = h - padding - halfLabelHeight;
        if (!Float.isFinite(value)) {
            float emptySize = compact ? 14 : EnergyWidgetStyle.CHART_MESSAGE_SP;
            paint.setTextSize(emptySize * fontScale);
            metrics = paint.getFontMetrics();
            float baseline = (chartTop + h - padding) / 2 - (metrics.ascent + metrics.descent) / 2;
            float center = (axisX + right) / 2;
            if (paint.measureText("Нет данных") <= right - axisX) {
                text(
                        canvas,
                        "Нет данных",
                        center,
                        baseline,
                        emptySize,
                        EnergyWidgetStyle.MUTED,
                        Paint.Align.CENTER);
            } else {
                float lineHeight = (emptySize + 2) * fontScale;
                text(
                        canvas,
                        "Нет",
                        center,
                        baseline - lineHeight / 2,
                        emptySize,
                        EnergyWidgetStyle.MUTED,
                        Paint.Align.CENTER);
                text(
                        canvas,
                        "данных",
                        center,
                        baseline + lineHeight / 2,
                        emptySize,
                        EnergyWidgetStyle.MUTED,
                        Paint.Align.CENTER);
            }
        } else if (bottom - top >= 8 && right - left >= 20) {
            paint.setStrokeWidth(1);
            paint.setColor(EnergyWidgetStyle.BORDER);
            canvas.drawLine(left, top, right, top, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);
            text(
                    canvas,
                    "100",
                    axisX,
                    top + labelOffset,
                    axisSize,
                    EnergyWidgetStyle.MUTED,
                    Paint.Align.LEFT);
            text(
                    canvas,
                    "0",
                    axisX,
                    bottom + labelOffset,
                    axisSize,
                    EnergyWidgetStyle.MUTED,
                    Paint.Align.LEFT);
            List<SystemLoadHistory.Point> points = history.points();
            line.reset();
            SystemLoadHistory.Point previous = null;
            float lastX = 0, lastY = 0;
            boolean hasPoint = false;
            paint.setColor(accent);
            for (SystemLoadHistory.Point point : points) {
                float v = cpu ? point.cpu : point.ram;
                if (!Float.isFinite(v)
                        || now < point.elapsed
                        || now - point.elapsed > SystemLoadHistory.WINDOW_MS) {
                    previous = null;
                    continue;
                }
                float x =
                        right
                                - (float) (now - point.elapsed)
                                        / SystemLoadHistory.WINDOW_MS
                                        * (right - left);
                float y = bottom - v / 100 * (bottom - top);
                if (previous == null || !SystemLoadHistory.joins(previous, point)) {
                    line.moveTo(x, y);
                    canvas.drawCircle(x, y, 1.5f, paint);
                } else {
                    line.lineTo(x, y);
                }
                previous = point;
                lastX = x;
                lastY = y;
                hasPoint = true;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            if (hasPoint) {
                canvas.drawCircle(lastX, lastY, 2.5f, paint);
            }
        }
        canvas.restore();
    }
}
