package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.graphics.Canvas;

import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.restoremode.R;

import java.util.Locale;

public final class TripWidgetView extends VehicleWidgetView {
    // Native button: 52 dp, 12 dp bottom margin, and 8 dp above it.
    private static final float TRIP_HISTORY_FOOTER = 72;
    private long tripDurationMillis = -1;
    private boolean tripHistoryVisible;

    public TripWidgetView(Context context, int columns, int rows) {
        super(context, VehicleWidgets.TRIP, columns, rows);
    }

    @Override
    protected boolean usesResponsiveLayout() {
        return true;
    }

    @Override
    protected float footerHeight() {
        return tripHistoryVisible
                ? TRIP_HISTORY_FOOTER * getResources().getDisplayMetrics().density
                : 0;
    }

    @Override
    protected String description() {
        return "Текущая поездка";
    }

    public void timer(long milliseconds) {
        tripDurationMillis = milliseconds;
        invalidate();
    }

    public void showTripHistory(boolean visible) {
        if (tripHistoryVisible != visible) {
            tripHistoryVisible = visible;
            invalidate();
        }
    }

    private float tripValue(String key, int index) {
        float[] a = floatArray(key, index + 1);
        return state.getInt(EnergyWidgetProtocol.SCHEMA) == EnergyWidgetProtocol.VERSION
                        && a.length > index
                ? a[index]
                : Float.NaN;
    }

    private void metric(
            Canvas canvas,
            String value,
            String unit,
            float x,
            float baseline,
            float width,
            int color) {
        float valueWidth = measureText(value, valueFont());
        drawText(canvas, value, x, baseline, valueFont(), color, false);
        if (unit.isEmpty()) {
            return;
        }
        if (valueWidth + 8 + measureText(unit, unitFont()) <= width) {
            drawText(canvas, unit, x + valueWidth + 8, baseline, unitFont(), color, false);
        } else {
            drawText(canvas, unit, x, baseline + 26 * (unitFont() / 20), unitFont(), color, false);
        }
    }

    @Override
    protected void drawWidget(Canvas canvas) {
        float height = contentHeight;
        boolean vertical = EnergyWidgetLayout.vertical(VehicleWidgets.TRIP, columns),
                compact = columns <= 6;
        float fontScale =
                getResources().getDisplayMetrics().scaledDensity
                        / getResources().getDisplayMetrics().density;
        float pad = 18, rightEdge = contentWidth - pad, content = contentWidth - 2 * pad;
        drawText(
                canvas,
                "Текущая поездка",
                pad,
                28 * fontScale,
                fontSize(R.dimen.energy_title_text_size),
                WHITE,
                true);
        if (tripDurationMillis < 0) {
            if (vertical) {
                drawText(
                        canvas, "Ожидание статуса", pad, 60 * fontScale, labelFont(), MUTED, false);
            } else {
                drawRightAlignedText(
                        canvas, "Ожидание статуса", rightEdge, 28 * fontScale, labelFont(), MUTED);
            }
        }
        String time = "—";
        if (tripDurationMillis >= 0) {
            long s = tripDurationMillis / 1000;
            time = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        String[] labels = {"Время в пути", "Пробег", "Электричество", "Бензин"};
        String[] values = {
            time,
            formatNumber(tripValue(EnergyWidgetProtocol.TRIP, 0)),
            formatEstimate(tripValue(EnergyWidgetProtocol.TRIP, 1)),
            formatEstimate(tripValue(EnergyWidgetProtocol.TRIP, 2))
        };
        String[] units = {"", "км", "кВт·ч/100 км", "л/100 км"};
        float first =
                vertical
                        ? 86 * fontScale
                        : compact
                                ? 76 * fontScale
                                : Math.max(62 * fontScale, height - 96 * fontScale);
        float step =
                vertical
                        ? (height - first - 134 * fontScale) / 3
                        : compact ? height - first - 112 * fontScale : 0;
        for (int i = 0; i < 4; i++) {
            int row = vertical ? i : compact ? i % 2 : 0,
                    column = vertical ? 0 : compact ? i / 2 : i;
            float cell = vertical ? content : content / (compact ? 2 : 4), x = pad + column * cell;
            float labelY = first + row * step, valueY = labelY + 46 * fontScale;
            drawText(canvas, labels[i], x, labelY, labelFont(), MUTED, false);
            int color = i == 2 ? GREEN : i == 3 ? BLUE : WHITE;
            metric(canvas, values[i], units[i], x, valueY, vertical ? cell : cell - 12, color);
            if (!vertical && column > 0) {
                drawLine(
                        canvas,
                        x - 10,
                        labelY - 20 * fontScale,
                        x - 10,
                        valueY + 16 * fontScale,
                        BORDER,
                        1);
            }
        }
        float evKm = tripValue(EnergyWidgetProtocol.TRIP_OBSERVED_KM, 0),
                fuelKm = tripValue(EnergyWidgetProtocol.TRIP_OBSERVED_KM, 1);
        String note =
                !hasLiveData()
                        ? "Нет связи с автомобилем"
                        : Math.max(evKm, fuelKm) < 1
                                ? "Средние после 1 км наблюдения"
                                : Math.abs(evKm - fuelKm) < .1
                                        ? "Учтено " + formatNumber(Math.min(evKm, fuelKm)) + " км"
                                        : "Учтено: электро "
                                                + formatNumber(evKm)
                                                + ", бензин "
                                                + formatNumber(fuelKm)
                                                + " км";
        drawWrappedText(
                canvas,
                note,
                pad,
                height - (vertical ? 36 : 4) * fontScale,
                captionFont(),
                content,
                MUTED);
    }
}
