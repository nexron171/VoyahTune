package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;

import ru.big.town.common.EnergyWidgetProtocol;

public final class ConsumptionWidgetView extends VehicleWidgetView {
    private EnergyConsumptionLayout chartLayout;
    private int selectedSampleIndex = -1;
    private EnergyConsumptionChart consumption =
            new EnergyConsumptionChart(0, null, null, null, null, null);

    public ConsumptionWidgetView(Context context, int columns, int rows) {
        super(context, VehicleWidgets.CONSUMPTION, columns, rows);
    }

    @Override
    protected boolean usesResponsiveLayout() {
        return true;
    }

    @Override
    protected String description() {
        return "Расход электричества и бензина за последние 2,5 км. Шаг 100 метров";
    }

    @Override
    protected void onTap(float x, float y) {
        EnergyConsumptionLayout chart = chartLayout;
        selectedSampleIndex =
                chart != null && chart.contains(x, y) && selectedSampleIndex < 0
                        ? consumption.nearest((x - chart.left) / (chart.right - chart.left))
                        : -1;
        invalidate();
    }

    @Override
    protected void onStateChanged(Bundle previous) {
        double selectedEnd =
                selectedSampleIndex >= 0 && selectedSampleIndex < consumption.end.length
                        ? consumption.end[selectedSampleIndex]
                        : Double.NaN;
        consumption =
                new EnergyConsumptionChart(
                        recordedKm(),
                        state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START),
                        state.getDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END),
                        state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV),
                        state.getFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL),
                        state.getBooleanArray(EnergyWidgetProtocol.CONSUMPTION_BREAK));
        selectedSampleIndex = -1;
        for (int i = 0; i < consumption.end.length; i++) {
            if (consumption.end[i] == selectedEnd) {
                selectedSampleIndex = i;
                break;
            }
        }
    }

    private static String axisQuantity(double value) {
        if (value == 0) {
            return "0";
        }
        if (Math.abs(value) >= 1000 || Math.abs(value) < .001) {
            return String.format(RU, "%.1e", value);
        }
        return String.format(RU, "%.3f", value).replaceAll("0+$", "").replaceAll(",$", "");
    }

    @Override
    protected void drawWidget(Canvas canvas) {
        float fontScale =
                getResources().getDisplayMetrics().scaledDensity
                        / getResources().getDisplayMetrics().density;
        EnergyConsumptionLayout chart =
                new EnergyConsumptionLayout(contentWidth, contentHeight, columns, rows, fontScale);
        chartLayout = chart;
        float left = chart.left,
                rightEdge = chart.right,
                top = chart.top,
                bottom = chart.bottom,
                axisSize = (chart.compact ? 10 : EnergyWidgetStyle.CHART_AXIS_SP) * fontScale;
        float titleSize = (chart.compact ? 18 : EnergyWidgetStyle.CHART_TITLE_SP) * fontScale,
                unitSize = (chart.compact ? 12 : EnergyWidgetStyle.CHART_UNIT_SP) * fontScale;
        drawText(canvas, "Расход", chart.textInset, chart.title, titleSize, WHITE, false);
        float litersWidth = measureText("л", unitSize);
        drawRightAlignedText(
                canvas,
                "кВт·ч",
                contentWidth - chart.textInset - litersWidth - 12,
                chart.title,
                unitSize,
                GREEN);
        drawRightAlignedText(
                canvas, "л", contentWidth - chart.textInset, chart.title, unitSize, BLUE);
        String step = Math.round(EnergyWidgetProtocol.CONSUMPTION_STEP_KM * 1000) + " м";
        float zero = top + (float) consumption.zero() * (bottom - top);
        paint.setColor(0x0970e1ab);
        canvas.drawRect(left, zero, rightEdge, bottom, paint);
        drawLine(canvas, left, top, rightEdge, top, BORDER, 1);
        drawLine(canvas, left, bottom, rightEdge, bottom, BORDER, 1);
        drawLine(canvas, left, zero, rightEdge, zero, 0xff616b7b, 1);
        if (chart.axes) {
            if (consumption.electricMax > 0) {
                drawRightAlignedText(
                        canvas,
                        axisQuantity(consumption.electricMax),
                        left - 5,
                        top + 3,
                        axisSize,
                        GREEN);
            }
            drawRightAlignedText(canvas, "0", left - 5, zero + 3, axisSize, GREEN);
            if (consumption.electricMin < 0) {
                drawRightAlignedText(
                        canvas,
                        axisQuantity(consumption.electricMin),
                        left - 5,
                        bottom + 3,
                        axisSize,
                        GREEN);
            }
            if (consumption.fuelMax > 0) {
                drawText(
                        canvas,
                        axisQuantity(consumption.fuelMax),
                        rightEdge + 5,
                        top + 3,
                        axisSize,
                        BLUE,
                        false);
            }
            drawText(canvas, "0", rightEdge + 5, zero + 3, axisSize, BLUE, false);
        }
        int count = consumption.end.length,
                marker = selectedSampleIndex >= 0 ? selectedSampleIndex : count - 1;
        for (int series = 0; series < 2; series++) {
            final boolean electric = series == 0;
            float[] values = electric ? consumption.battery : consumption.fuel;
            int color = electric ? GREEN : BLUE;
            Path path = new Path();
            EnergyChartCurve.trace(
                    consumption.position,
                    values,
                    consumption.gaps,
                    count,
                    0,
                    electric ? Double.NEGATIVE_INFINITY : 0,
                    Double.POSITIVE_INFINITY,
                    new EnergyChartCurve.Sink() {
                        private float x(double position) {
                            return left
                                    + (float) (position / EnergyConsumptionChart.WINDOW)
                                            * (rightEdge - left);
                        }

                        private float y(double value) {
                            return top
                                    + (float)
                                                    (electric
                                                            ? consumption.electricPosition(value)
                                                            : consumption.fuelPosition(value))
                                            * (bottom - top);
                        }

                        @Override
                        public void moveTo(double position, double value) {
                            path.moveTo(x(position), y(value));
                        }

                        @Override
                        public void cubicTo(
                                double x1, double y1, double x2, double y2, double x, double y) {
                            path.cubicTo(x(x1), y(y1), x(x2), y(y2), x(x), y(y));
                        }
                    });
            paint.setColor(color);
            paint.setStrokeWidth(2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeJoin(Paint.Join.MITER);
            paint.setStyle(Paint.Style.FILL);
            if (marker >= 0 && Float.isFinite(values[marker])) {
                float x =
                        left
                                + (float)
                                                (consumption.position[marker]
                                                        / EnergyConsumptionChart.WINDOW)
                                        * (rightEdge - left);
                float y =
                        top
                                + (float)
                                                (electric
                                                        ? consumption.electricPosition(
                                                                values[marker])
                                                        : consumption.fuelPosition(values[marker]))
                                        * (bottom - top);
                canvas.drawCircle(x, y, chart.markerRadius, paint);
            }
        }
        if (selectedSampleIndex >= 0) {
            float x =
                    left
                            + (float)
                                            (consumption.position[selectedSampleIndex]
                                                    / EnergyConsumptionChart.WINDOW)
                                    * (rightEdge - left);
            drawLine(canvas, x, top, x, bottom, 0xff7b8799, 1);
        }
        if (count == 0) {
            drawWrappedText(
                    canvas,
                    !hasLiveData() ? "Нет данных" : "Ожидание " + step,
                    left,
                    (top + bottom) / 2 + 5,
                    (chart.compact ? 14 : EnergyWidgetStyle.CHART_MESSAGE_SP) * fontScale,
                    rightEdge - left,
                    MUTED);
        }
    }
}
