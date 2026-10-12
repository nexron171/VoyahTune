package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.graphics.Canvas;

import ru.big.town.common.EnergyWidgetProtocol;

public final class OdometerWidgetView extends VehicleWidgetView {
    private final Runnable clockTick =
            new Runnable() {
                @Override
                public void run() {
                    invalidate();
                    postDelayed(this, 30_000);
                }
            };

    public OdometerWidgetView(Context context, int columns, int rows) {
        super(context, VehicleWidgets.ODO, columns, rows);
    }

    @Override
    protected String description() {
        return "Общий пробег";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(clockTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(clockTick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawWidget(Canvas canvas) {
        float pad = columns == 2 ? 20 : columns == 3 ? 22 : 27;
        float odo = currentValue(EnergyWidgetProtocol.ODOMETER, 0);
        String value = Float.isFinite(odo) ? String.format(RU, "%,.0f", odo) : "—";
        if (columns == 2) {
            drawText(canvas, "Общий пробег", pad, 33, 20, WHITE, true);
            drawText(canvas, "ODO", 166, 33, 14, MUTED, false);
            valueWithUnit(canvas, value, "км", pad, 74, 35, 18, WHITE);
        } else {
            drawText(canvas, "Общий пробег", pad, 42, columns == 3 ? 20 : 23, WHITE, true);
            drawText(canvas, "ODO", pad, 66, 17, MUTED, false);
            float size = columns == 3 ? 38 : 49, end = contentWidth - pad;
            drawRightAlignedText(canvas, "км", end, 77, columns == 3 ? 18 : 22, MUTED);
            drawRightAlignedText(canvas, value, end - 42, 78, size, WHITE);
        }
        String date =
                new java.text.SimpleDateFormat("dd.MM.yyyy", RU).format(new java.util.Date());
        drawText(canvas, date, pad, contentHeight - 13, columns == 2 ? 14 : 16, MUTED, false);
    }
}
