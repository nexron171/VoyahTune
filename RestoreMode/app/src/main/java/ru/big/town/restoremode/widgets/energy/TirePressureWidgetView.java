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
                        try (InputStream inputStream =
                                context.getApplicationContext()
                                        .getAssets()
                                        .open("energy/car_" + this.color + ".png")) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = 2;
                            Bitmap body = BitmapFactory.decodeStream(inputStream, null, options);
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
        boolean shorter = rows == 3;
        float padding = 16;
        float titleSize = 20;
        float titleY = shorter ? 40 : 55;
        drawText(canvas, "Давление в шинах", padding, titleY, titleSize, WHITE, true);
        drawRightAlignedText(
                canvas, "bar", contentWidth - padding, titleY, 14, MUTED);
        float height = shorter ? 258 : 325;
        float top = shorter ? 60 : 78;
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
        float pressureSize = shorter ? 30 : 36;
        float plateWidth = 88;
        for (int wheelIndex = 0; wheelIndex < 4; wheelIndex++) {
            boolean left = wheelIndex % 2 == 0;
            float valueY = shorter ? (wheelIndex < 2 ? 150 : 287) : (wheelIndex < 2 ? 198 : 373);
            float plateLeft = left ? padding : contentWidth - padding - plateWidth;
            paint.setColor(0x7a000000);
            canvas.drawRoundRect(
                    plateLeft, valueY - pressureSize - 4,
                    plateLeft + plateWidth, valueY + 8, 4, 4, paint);
            String pressure = formatNumber(currentValue(EnergyWidgetProtocol.TIRES, wheelIndex));
            if (left) {
                drawText(canvas, pressure, padding + 8, valueY, pressureSize, WHITE, false);
            } else {
                drawRightAlignedText(
                        canvas, pressure, contentWidth - padding - 8, valueY, pressureSize, WHITE);
            }
        }
        if (!hasLiveData()) {
            drawText(
                    canvas,
                    "Нет связи",
                    padding,
                    shorter ? contentHeight - 27 : 462,
                    12,
                    MUTED,
                    false);
        }
    }
}
