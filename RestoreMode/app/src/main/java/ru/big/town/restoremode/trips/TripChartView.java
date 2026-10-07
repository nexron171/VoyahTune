package ru.big.town.restoremode.trips;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.energy.EnergyChartCurve;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetStyle;

import java.util.List;
import java.util.Locale;

/** Full-trip percentage and estimated consumption charts; gaps never become connecting lines. */
final class TripChartView extends View {
    private final List<double[]> points;
    private final boolean consumption;
    private final double battery, tank;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] distance;
    private final float[][] series;
    private final boolean[] gaps;
    private final double[] scales = {100, 100};
    private double electricMin;

    TripChartView(
            Context c, List<double[]> points, boolean consumption, double battery, double tank) {
        super(c);
        this.points = points;
        this.consumption = consumption;
        this.battery = battery;
        this.tank = tank;
        distance = new float[points.size()];
        series = new float[2][points.size()];
        gaps = new boolean[points.size()];
        if (consumption) {
            scales[0] = scales[1] = 1;
        }
        for (int i = 0; i < points.size(); i++) {
            distance[i] = (float) points.get(i)[0];
            gaps[i] = points.get(i)[8] != 0;
            for (int channel = 0; channel < 2; channel++) {
                series[channel][i] = (float) value(i, channel);
                if (consumption && Float.isFinite(series[channel][i])) {
                    scales[channel] = Math.max(scales[channel], series[channel][i]);
                    if (channel == 0) {
                        electricMin = Math.min(electricMin, series[channel][i]);
                    }
                }
            }
        }
        if (consumption) {
            for (int channel = 0; channel < 2; channel++) {
                scales[channel] = Math.ceil(scales[channel] / 5) * 5;
            }
        }
        if (consumption) {
            electricMin = Math.floor(electricMin / 5) * 5;
        }
        setContentDescription(
                consumption
                        ? "График расхода электричества и бензина по пробегу поездки"
                        : "График заряда батареи и уровня бензина по пробегу поездки");
    }

    private double value(int index, int channel) {
        double[] p = points.get(index);
        if (!consumption) {
            return p[2 + channel];
        }
        if (index == 0 || p[8] != 0) {
            return Double.NaN;
        }
        double[] before = points.get(index - 1);
        return TripHistoryPresentation.rate(
                before[4 + channel],
                p[4 + channel],
                before[6 + channel],
                p[6 + channel],
                channel == 0 ? battery : tank,
                channel == 0);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float density = getResources().getDisplayMetrics().density;
        c.save();
        c.scale(density, density);
        float width = getWidth() / density, height = getHeight() / density;
        float f = getResources().getDisplayMetrics().scaledDensity / density;
        float left = 80 * f,
                right = width - (consumption ? 80 * f : 24),
                top = consumption ? 72 * f : 48 * f,
                bottom = height - 36 * f;
        double maxKm = Math.max(.1, points.get(points.size() - 1)[0]);
        text(
                c,
                consumption ? "Расход" : "Уровни, %",
                left,
                26 * f,
                font(R.dimen.energy_label_text_size),
                EnergyWidgetStyle.MUTED);
        if (consumption) {
            text(
                    c,
                    "кВт·ч/100 км",
                    left,
                    52 * f,
                    font(R.dimen.energy_unit_text_size),
                    EnergyWidgetStyle.GREEN);
            right(
                    c,
                    "л/100 км",
                    right,
                    52 * f,
                    font(R.dimen.energy_unit_text_size),
                    EnergyWidgetStyle.BLUE);
        }
        int ticks = bottom - top >= 96 * f ? 4 : 2;
        for (int i = 0; i <= ticks; i++) {
            float y = bottom - (bottom - top) * i / ticks;
            paint.setColor(EnergyWidgetStyle.BORDER);
            paint.setStrokeWidth(1);
            c.drawLine(left, y, right, y, paint);
            right(
                    c,
                    tick(electricMin + (scales[0] - electricMin) * i / ticks)
                            + (consumption ? "" : "%"),
                    left - 8,
                    y + 5,
                    font(R.dimen.energy_caption_text_size),
                    consumption ? EnergyWidgetStyle.GREEN : EnergyWidgetStyle.MUTED);
            if (consumption) {
                text(
                        c,
                        tick(scales[1] * i / ticks),
                        right + 8,
                        y + 5,
                        font(R.dimen.energy_caption_text_size),
                        EnergyWidgetStyle.BLUE);
            }
        }
        for (int i = 0; i <= 4; i++) {
            text(
                    c,
                    String.format(Locale.US, "%.1f", maxKm * i / 4),
                    left + (right - left) * i / 4 - 10,
                    bottom + 28 * f,
                    font(R.dimen.energy_caption_text_size),
                    EnergyWidgetStyle.MUTED);
        }
        for (int k = 0; k < 2; k++) {
            int color = k == 0 ? EnergyWidgetStyle.GREEN : EnergyWidgetStyle.BLUE;
            double scale = scales[k], min = k == 0 ? electricMin : 0;
            Path path = new Path();
            EnergyChartCurve.trace(
                    distance,
                    series[k],
                    gaps,
                    distance.length,
                    0,
                    min,
                    scale,
                    new EnergyChartCurve.Sink() {
                        private float x(double km) {
                            return left + (float) (km / maxKm) * (right - left);
                        }

                        private float y(double value) {
                            return bottom
                                    - (float) ((value - min) / (scale - min)) * (bottom - top);
                        }

                        @Override
                        public void moveTo(double km, double value) {
                            path.moveTo(x(km), y(value));
                        }

                        @Override
                        public void cubicTo(
                                double km1,
                                double value1,
                                double km2,
                                double value2,
                                double km,
                                double value) {
                            path.cubicTo(x(km1), y(value1), x(km2), y(value2), x(km), y(value));
                        }
                    });
            paint.setColor(color);
            paint.setStrokeWidth(EnergyWidgetStyle.LINE_WIDTH);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            int last = distance.length - 1;
            if (last >= 0 && Float.isFinite(series[k][last])) {
                c.drawCircle(
                        left + (float) (distance[last] / maxKm) * (right - left),
                        bottom - (float) ((series[k][last] - min) / (scale - min)) * (bottom - top),
                        3,
                        paint);
            }
        }
        c.restore();
    }

    private void text(Canvas c, String s, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(s, x, y, paint);
    }

    private float font(int resource) {
        return getResources().getDimension(resource) / getResources().getDisplayMetrics().density;
    }

    private void right(Canvas c, String s, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(size);
        text(c, s, x - paint.measureText(s), y, size, color);
    }

    private static String tick(double value) {
        return String.format(Locale.getDefault(), value > 0 && value < 10 ? "%.1f" : "%.0f", value);
    }
}
