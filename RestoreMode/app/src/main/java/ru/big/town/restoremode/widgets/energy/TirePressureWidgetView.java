package ru.big.town.restoremode.widgets.energy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.LruCache;

import ru.big.town.common.EnergyWidgetProtocol;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TirePressureWidgetView extends VehicleWidgetView {
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final LruCache<String, EnergyCarImage> CACHE = new LruCache<>(2);
    private final String color;
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private EnergyCarImage car;

    public TirePressureWidgetView(Context context, String color, int columns, int rows) {
        super(context, VehicleWidgets.TIRES, columns, rows);
        this.color = CarAppearance.normalizeColor(color);
        car = CACHE.get(this.color);
        if (car == null) {
            IMAGES.execute(
                    () -> {
                        EnergyCarImage loaded = null;
                        try (InputStream in =
                                context.getApplicationContext()
                                        .getAssets()
                                        .open("energy/car_" + this.color + ".png")) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = 2;
                            Bitmap body = BitmapFactory.decodeStream(in, null, options);
                            if (body != null) {
                                loaded = new EnergyCarImage(body);
                            }
                        } catch (Exception ignored) {
                        }
                        if (loaded != null) {
                            CACHE.put(this.color, loaded);
                        }
                        EnergyCarImage result = loaded;
                        post(
                                () -> {
                                    car = result;
                                    invalidate();
                                });
                    });
        }
    }

    @Override
    protected String description() {
        return "Давление в шинах, bar. Левое переднее "
                + formatNumber(currentValue(EnergyWidgetProtocol.TIRES, 0))
                + ", правое переднее "
                + formatNumber(currentValue(EnergyWidgetProtocol.TIRES, 1))
                + ", левое заднее "
                + formatNumber(currentValue(EnergyWidgetProtocol.TIRES, 2))
                + ", правое заднее "
                + formatNumber(currentValue(EnergyWidgetProtocol.TIRES, 3));
    }

    @Override
    protected void drawWidget(Canvas canvas) {
        boolean narrow = columns < 4, shorter = rows == 3;
        float pad = columns == 2 ? 16 : narrow ? 20 : 27,
                titleSize = columns == 2 ? 20 : narrow ? 24 : 28;
        float titleY = shorter ? 40 : 55;
        drawText(canvas, "Давление в шинах", pad, titleY, titleSize, WHITE, true);
        drawRightAlignedText(
                canvas, "bar", contentWidth - pad, titleY, columns == 2 ? 14 : 19, MUTED);
        float height = shorter ? 258 : columns == 2 ? 325 : columns == 3 ? 335 : 345,
                top = shorter ? 60 : 78;
        if (car != null) {
            car.draw(canvas, carPaint, contentWidth / 2, top, height);
        } else {
            drawText(
                    canvas,
                    "Загрузка…",
                    contentWidth / 2 - 42,
                    contentHeight / 2,
                    16,
                    MUTED,
                    false);
        }
        String[] names = {"Левое переднее", "Правое переднее", "Левое заднее", "Правое заднее"};
        float pressureSize =
                shorter
                        ? (columns == 2 ? 30 : columns == 3 ? 34 : 40)
                        : (columns == 2 ? 36 : columns == 3 ? 42 : 53);
        float labelSize = columns == 2 ? 12 : columns == 3 ? 14 : 17,
                plateW = columns == 2 ? 120 : 137;
        for (int i = 0; i < 4; i++) {
            boolean left = i % 2 == 0;
            float y = shorter ? (i < 2 ? 110 : 247) : (i < 2 ? 152 : 327);
            float valueY = y + pressureSize + 10;
            if (narrow) {
                paint.setColor(0x7a000000);
                float x = left ? pad : contentWidth - pad - plateW;
                canvas.drawRoundRect(x, y - labelSize - 8, x + plateW, valueY + 8, 4, 4, paint);
            }
            float x = left ? pad + (narrow ? 8 : 0) : contentWidth - pad - (narrow ? 8 : 0);
            if (left) {
                drawText(canvas, names[i], x, y, labelSize, MUTED, false);
                drawText(
                        canvas,
                        formatNumber(currentValue(EnergyWidgetProtocol.TIRES, i)),
                        x,
                        valueY,
                        pressureSize,
                        WHITE,
                        false);
            } else {
                drawRightAlignedText(canvas, names[i], x, y, labelSize, MUTED);
                drawRightAlignedText(
                        canvas,
                        formatNumber(currentValue(EnergyWidgetProtocol.TIRES, i)),
                        x,
                        valueY,
                        pressureSize,
                        WHITE);
            }
            if (!narrow) {
                drawLine(
                        canvas,
                        left ? 162 : contentWidth - 192,
                        y + 32,
                        left ? 192 : contentWidth - 162,
                        y + 32,
                        BORDER,
                        1);
            }
        }
        if (!hasLiveData()) {
            drawText(
                    canvas,
                    "Нет связи",
                    pad,
                    shorter ? contentHeight - 27 : 462,
                    columns == 2 ? 12 : columns == 3 ? 13 : 16,
                    MUTED,
                    false);
        }
    }
}
