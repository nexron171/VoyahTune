package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;

import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.common.EnergyWidgetSettings;
import ru.big.town.restoremode.R;

public final class EnergyWidgetView extends VehicleWidgetView {
    private final SharedPreferences preferences;
    private EnergyChartLayout chartLayout;
    private int historyWindowKm;
    private int selectedHistoryIndex = -1;

    public EnergyWidgetView(Context context, int columns, int rows) {
        super(context, VehicleWidgets.ENERGY, columns, rows);
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        historyWindowKm =
                EnergyWidgetSettings.window(
                        preferences.getInt(
                                EnergyWidgetSettings.WINDOW_KEY,
                                EnergyWidgetSettings.DEFAULT_WINDOW_KM));
        preferences.edit().putInt(EnergyWidgetSettings.WINDOW_KEY, historyWindowKm).apply();
    }

    @Override
    protected boolean usesResponsiveLayout() {
        return true;
    }

    @Override
    protected String description() {
        return "Заряд батареи: "
                + formatNumber(currentValue(EnergyWidgetProtocol.LEVELS, 0))
                + " процентов, топливо "
                + formatNumber(currentValue(EnergyWidgetProtocol.LEVELS, 1))
                + " процентов";
    }

    @Override
    protected void onTap(float x, float y) {
        EnergyChartLayout chart = chartLayout;
        if (chart == null) {
            return;
        }
        int slot = chart.windowSlot(x, y);
        if (slot >= 0) {
            historyWindowKm = EnergyWidgetSettings.WINDOWS[slot];
            selectedHistoryIndex = -1;
            preferences.edit().putInt(EnergyWidgetSettings.WINDOW_KEY, historyWindowKm).apply();
        } else if (chart.containsHistory(x, y)) {
            if (selectedHistoryIndex >= 0) {
                selectedHistoryIndex = -1;
            } else {
                float[] distances = floatArray(EnergyWidgetProtocol.HISTORY_X, 0);
                if (distances.length > 0) {
                    EnergyChartAxis axis =
                            new EnergyChartAxis(recordedKm(), historyWindowKm, Float.NaN);
                    double target = axis.distanceAt((x - chart.left) / (chart.right - chart.left)),
                            best = Double.MAX_VALUE;
                    for (int i = 0; i < distances.length; i++) {
                        if (axis.position(distances[i]) >= 0
                                && Math.abs(distances[i] - target) < best) {
                            best = Math.abs(distances[i] - target);
                            selectedHistoryIndex = i;
                        }
                    }
                }
            }
        } else {
            selectedHistoryIndex = -1;
        }
        invalidate();
    }

    @Override
    protected void onStateChanged(Bundle previous) {
        float[] previousDistances = previous.getFloatArray(EnergyWidgetProtocol.HISTORY_X);
        float selectedDistance =
                previousDistances != null
                                && selectedHistoryIndex >= 0
                                && selectedHistoryIndex < previousDistances.length
                        ? previousDistances[selectedHistoryIndex]
                        : Float.NaN;
        selectedHistoryIndex = -1;
        float[] distances = floatArray(EnergyWidgetProtocol.HISTORY_X, 0);
        for (int index = 0; index < distances.length; index++) {
            if (distances[index] == selectedDistance) {
                selectedHistoryIndex = index;
                break;
            }
        }
    }

    private float capacity(String key, float fallback) {
        float value = state.getFloat(key, fallback);
        return EnergyWidgetSettings.validCapacity(value) ? value : fallback;
    }

    @Override
    protected void drawWidget(Canvas canvas) {
        boolean vertical = EnergyWidgetLayout.vertical(VehicleWidgets.ENERGY, columns);
        float fontScale =
                getResources().getDisplayMetrics().scaledDensity
                        / getResources().getDisplayMetrics().density;
        EnergyChartLayout chart =
                new EnergyChartLayout(contentWidth, contentHeight, vertical, fontScale);
        chartLayout = chart;
        float pad = chart.pad, button = chart.buttonWidth, buttonsLeft = chart.buttonLeft;
        drawText(
                canvas,
                "Заряд и топливо",
                pad,
                32 * fontScale,
                fontSize(R.dimen.energy_title_text_size),
                WHITE,
                true);
        for (int i = 0; i < 3; i++) {
            int range = EnergyWidgetSettings.WINDOWS[i];
            float x = buttonsLeft + i * button;
            paint.setColor(range == historyWindowKm ? 0xff414b5c : 0xff1d212a);
            canvas.drawRoundRect(
                    x, chart.buttonTop, x + button - 4, chart.buttonBottom, 9, 9, paint);
            String label = range + " км";
            float control = fontSize(R.dimen.energy_control_text_size);
            drawText(
                    canvas,
                    label,
                    x + (button - 4 - measureText(label, control)) / 2,
                    chart.buttonBottom - 12 * fontScale,
                    control,
                    range == historyWindowKm ? WHITE : MUTED,
                    false);
        }
        float[] distances = floatArray(EnergyWidgetProtocol.HISTORY_X, 0),
                ev = floatArray(EnergyWidgetProtocol.HISTORY_EV, distances.length),
                fuel = floatArray(EnergyWidgetProtocol.HISTORY_FUEL, distances.length);
        boolean[] gaps = state.getBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK);
        int n = Math.min(distances.length, Math.min(ev.length, fuel.length));
        EnergyChartAxis axis =
                new EnergyChartAxis(
                        recordedKm(),
                        historyWindowKm,
                        currentValue(EnergyWidgetProtocol.ODOMETER, 0));
        float currentEv = currentValue(EnergyWidgetProtocol.LEVELS, 0),
                currentFuel = currentValue(EnergyWidgetProtocol.LEVELS, 1);
        boolean hasCurrent = Float.isFinite(currentEv) || Float.isFinite(currentFuel);
        if (selectedHistoryIndex >= 0 && selectedHistoryIndex < n) {
            currentEv = ev[selectedHistoryIndex];
            currentFuel = fuel[selectedHistoryIndex];
        }
        float batteryCapacity = capacity(EnergyWidgetProtocol.BATTERY_KWH, 43),
                tankCapacity = capacity(EnergyWidgetProtocol.TANK_LITERS, 56);
        float gap = 12,
                cell = (contentWidth - 2 * pad - (vertical ? 1 : 3) * gap) / (vertical ? 2 : 4);
        for (int i = 0; i < 2; i++) {
            int color = i == 0 ? GREEN : BLUE;
            float value = i == 0 ? currentEv : currentFuel;
            float x = pad + (vertical ? i : 2 * i) * (cell + gap);
            drawText(
                    canvas,
                    i == 0 ? "Батарея, %" : "Топливо, %",
                    x,
                    chart.readingLabel,
                    labelFont(),
                    MUTED,
                    false);
            drawText(canvas, formatNumber(value), x, chart.readingValue, valueFont(), color, false);
            float restX = vertical ? x : x + cell + gap;
            String rest = (cell < 180 ? "Ост., " : "Осталось, ") + (i == 0 ? "кВт·ч" : "л");
            drawText(canvas, rest, restX, chart.remainingLabel, labelFont(), MUTED, false);
            drawText(
                    canvas,
                    formatEstimate(value * (i == 0 ? batteryCapacity : tankCapacity) / 100),
                    restX,
                    chart.remainingValue,
                    valueFont(),
                    color,
                    false);
        }
        String status =
                selectedHistoryIndex >= 0
                        ? (Double.isFinite(axis.odometerAt(distances[selectedHistoryIndex]))
                                ? "Пробег ~"
                                        + formatMileage(
                                                axis.odometerAt(distances[selectedHistoryIndex]))
                                        + " км"
                                : "Пробег недоступен")
                        : !hasLiveData() ? "Нет связи" : hasCurrent ? "" : "Нет свежих данных";
        if (!status.isEmpty()) {
            drawRightAlignedText(
                    canvas, status, chart.split, chart.levelsTitle, captionFont(), MUTED);
        }
        float left = chart.left, rightEdge = chart.right, top = chart.top, bottom = chart.bottom;
        int ticks = bottom - top < 48 ? 1 : bottom - top < 100 ? 2 : 4;
        for (int i = 0; i <= ticks; i++) {
            float y = top + (bottom - top) * i / ticks;
            drawLine(canvas, left, y, rightEdge, y, BORDER, 1);
            String label = (100 - 100 * i / ticks) + "%";
            drawRightAlignedText(canvas, label, left - 8, y + 5, captionFont(), MUTED);
            drawText(canvas, label, rightEdge + 8, y + 5, captionFont(), MUTED, false);
        }
        int intervals = Math.max(2, Math.min(6, (int) ((rightEdge - left) / (110 * fontScale))));
        double endOdo = axis.tick(0);
        float endWidth = measureText(formatMileage(endOdo), captionFont()),
                endLeft = rightEdge - endWidth,
                tickRight = Float.NEGATIVE_INFINITY;
        for (int i = intervals; i >= 0; i--) {
            float x = rightEdge - (rightEdge - left) * i / intervals;
            String label = formatMileage(endOdo - historyWindowKm * i / (double) intervals);
            float labelWidth = measureText(label, captionFont());
            float start = i == intervals ? x : i == 0 ? x - labelWidth : x - labelWidth / 2;
            boolean endpoint =
                    i == 0 || (i == intervals && labelWidth + endWidth + 12 <= rightEdge - left);
            boolean middle =
                    i > 0
                            && i < intervals
                            && start >= tickRight + 12
                            && start + labelWidth <= endLeft - 12;
            if (endpoint || middle) {
                drawText(canvas, label, start, chart.axis, captionFont(), MUTED, false);
                tickRight = start + labelWidth;
            }
        }
        if (n < 2) {
            drawWrappedText(
                    canvas,
                    !hasLiveData() ? "Нет истории" : "Ожидание истории",
                    left + 6,
                    (top + bottom) / 2 + 5,
                    labelFont(),
                    rightEdge - left - 12,
                    MUTED);
        }
        for (int series = 0; series < 2; series++) {
            Path path = new Path();
            int color = series == 0 ? GREEN : BLUE;
            EnergyChartCurve.trace(
                    distances,
                    series == 0 ? ev : fuel,
                    gaps,
                    n,
                    axis.distanceAt(0),
                    new EnergyChartCurve.Sink() {
                        private float x(double km) {
                            return left + axis.position(km) * (rightEdge - left);
                        }

                        private float y(double level) {
                            return bottom - (float) (level / 100) * (bottom - top);
                        }

                        @Override
                        public void moveTo(double km, double level) {
                            path.moveTo(x(km), y(level));
                        }

                        @Override
                        public void cubicTo(
                                double km1,
                                double level1,
                                double km2,
                                double level2,
                                double km,
                                double level) {
                            path.cubicTo(x(km1), y(level1), x(km2), y(level2), x(km), y(level));
                        }
                    });
            paint.setColor(color);
            paint.setStrokeWidth(EnergyWidgetStyle.LINE_WIDTH);
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(null);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeJoin(Paint.Join.MITER);
            paint.setStyle(Paint.Style.FILL);
            int marker =
                    selectedHistoryIndex >= 0 && selectedHistoryIndex < n
                            ? selectedHistoryIndex
                            : n - 1;
            if (marker >= 0 && axis.position(distances[marker]) >= 0) {
                float v = series == 0 ? ev[marker] : fuel[marker];
                if (Float.isFinite(v) && v >= 0 && v <= 100) {
                    float x = left + axis.position(distances[marker]) * (rightEdge - left),
                            y = bottom - v / 100 * (bottom - top);
                    canvas.drawCircle(x, y, 3, paint);
                }
            }
        }
        if (selectedHistoryIndex >= 0
                && selectedHistoryIndex < n
                && axis.position(distances[selectedHistoryIndex]) >= 0) {
            float x = left + axis.position(distances[selectedHistoryIndex]) * (rightEdge - left);
            drawLine(canvas, x, top, x, bottom, 0xff7b8799, 1);
        }
        EnergyPeriodEstimate period =
                EnergyPeriodEstimate.recent(
                        distances,
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM),
                        batteryCapacity,
                        tankCapacity,
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM),
                        state.getDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM));
        int averageWindow = EnergyWidgetSettings.AVERAGE_WINDOW_KM;
        float coverage = Math.min(period.batteryKm, period.fuelKm);
        String note =
                Math.max(period.batteryKm, period.fuelKm) < 1
                        ? "Нужно от 1 км истории"
                        : Math.abs(period.batteryKm - period.fuelKm) > .1f
                                ? "Эл. "
                                        + formatNumber(period.batteryKm)
                                        + " · Бенз. "
                                        + formatNumber(period.fuelKm)
                                        + " из "
                                        + averageWindow
                                        + " км"
                                : coverage < averageWindow - .1f
                                        ? "Учтено "
                                                + formatNumber(coverage)
                                                + " из "
                                                + averageWindow
                                                + " км"
                                        : "";
        float periodWidth = chart.split - pad, periodCell = (periodWidth - 12) / 2;
        drawText(
                canvas,
                "Средний расход · " + averageWindow + " км",
                pad,
                chart.periodLabel,
                labelFont(),
                MUTED,
                false);
        drawText(canvas, "кВт·ч/100 км", pad, chart.periodUnits, unitFont(), GREEN, false);
        drawText(
                canvas,
                "л/100 км",
                pad + periodCell + 12,
                chart.periodUnits,
                unitFont(),
                BLUE,
                false);
        drawText(
                canvas,
                formatEstimate(period.battery),
                pad,
                chart.periodValue,
                valueFont(),
                GREEN,
                false);
        drawText(
                canvas,
                formatEstimate(period.fuel),
                pad + periodCell + 12,
                chart.periodValue,
                valueFont(),
                BLUE,
                false);
        if (!note.isEmpty()) {
            drawText(canvas, note, pad, chart.periodNote, captionFont(), MUTED, false);
        }
    }
}
