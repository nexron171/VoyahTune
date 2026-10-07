package ru.big.town.restoremode.settings.ui.preview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import ru.big.town.restoremode.R;

/** Decorative vectors and a live layout diagram. No simulated vehicle or microphone readings. */
public final class SettingsIllustration extends View
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final float ROAD_SCENE_WIDTH = 220;
    private static final float ROAD_SCENE_HEIGHT = 115;
    private static final int[] WAVE_COLUMN_HEIGHTS = {
        18, 32, 48, 24, 62, 76, 42, 26, 64, 50, 72, 34, 54, 22, 40
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences preferences;
    private Drawable rearCarIllustration;

    public SettingsIllustration(Context context, AttributeSet attrs) {
        super(context, attrs);
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        invalidate();
    }

    private void drawRoundedRectangle(
            Canvas canvas,
            float left,
            float top,
            float width,
            float height,
            float radius,
            int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, paint);
    }

    private void drawLine(
            Canvas canvas,
            float startX,
            float startY,
            float endX,
            float endY,
            int color,
            float strokeWidth) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        canvas.drawLine(startX, startY, endX, endY, paint);
    }

    private void drawCircle(Canvas canvas, float centerX, float centerY, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawCircle(centerX, centerY, radius, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.scale(density, density);
        float width = getWidth() / density;
        float height = getHeight() / density;

        switch (String.valueOf(getTag())) {
            case "settings.wave":
                drawWaveform(canvas, width, height);
                break;
            case "settings.grid-columns":
                drawGridColumns(canvas, width, height);
                break;
            case "settings.keyboard":
                drawKeyboard(canvas, width, height);
                break;
            case "settings.road":
                drawRoadScene(canvas, width, height);
                break;
        }

        canvas.restore();
    }

    private void drawWaveform(Canvas canvas, float width, float height) {
        for (int columnIndex = 0; columnIndex < WAVE_COLUMN_HEIGHTS.length; columnIndex++) {
            int columnHeight = WAVE_COLUMN_HEIGHTS[columnIndex];
            drawRoundedRectangle(
                    canvas,
                    (width - 189) / 2 + columnIndex * 13,
                    (height - columnHeight) / 2,
                    7,
                    columnHeight,
                    4,
                    0xff9ac4ff);
        }
    }

    private void drawGridColumns(Canvas canvas, float width, float height) {
        drawRoundedRectangle(canvas, 0, 0, width, height, 14, 0xff141d2a);
        int activeColumnCount =
                preferences.getBoolean("fullscreenGrid", false)
                        ? preferences.getInt("fullscreenGridColumns", 8)
                        : 0;
        int spacing = preferences.getInt("tileSpacingDp", 4);
        float gap = 4 + spacing / 2f;
        float columnWidth = (width - 28 - gap * 11) / 12;

        for (int columnIndex = 0; columnIndex < 12; columnIndex++) {
            boolean active = columnIndex < activeColumnCount;
            float columnHeight = active ? 65 : 44;
            drawRoundedRectangle(
                    canvas,
                    14 + columnIndex * (columnWidth + gap),
                    height - 14 - columnHeight,
                    columnWidth,
                    columnHeight,
                    6,
                    active ? 0xff668ebe : 0xff394d69);
        }
    }

    private void drawKeyboard(Canvas canvas, float width, float height) {
        String letters =
                "ru".equals(preferences.getString("keyboardMode", "off"))
                        ? "ЙЦУКЕНГШЩЗ"
                        : "QWERTYUIOP";
        float keyWidth = (width - 45) / 10;

        for (int keyIndex = 0; keyIndex < 10; keyIndex++) {
            float keyLeft = keyIndex * (keyWidth + 5);
            drawRoundedRectangle(canvas, keyLeft, 0, keyWidth, height, 6, 0xff394b63);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16);
            paint.setColor(0xfff3f5fa);
            canvas.drawText(
                    letters.substring(keyIndex, keyIndex + 1),
                    keyLeft + keyWidth / 2,
                    (height - paint.ascent() - paint.descent()) / 2,
                    paint);
        }
    }

    private void drawRoadScene(Canvas canvas, float width, float height) {
        float scale = Math.min(width / ROAD_SCENE_WIDTH, height / ROAD_SCENE_HEIGHT);
        canvas.save();
        canvas.translate(
                (width - ROAD_SCENE_WIDTH * scale) / 2,
                (height - ROAD_SCENE_HEIGHT * scale) / 2);
        canvas.scale(scale, scale);

        drawLine(canvas, 35, 110, 90, 5, 0xff587498, 3);
        drawLine(canvas, 185, 110, 130, 5, 0xff587498, 3);
        drawLine(canvas, 110, 17, 110, 33, 0xffa1bddb, 2);
        drawLine(canvas, 110, 50, 110, 62, 0xffa1bddb, 2);
        drawRearCar(canvas);
        drawSpeedLimitSign(canvas);
        drawTrafficLight(canvas);

        canvas.restore();
    }

    private void drawRearCar(Canvas canvas) {
        if (rearCarIllustration == null) {
            rearCarIllustration = getContext().getDrawable(R.drawable.settings_illustration_car_rear);
            if (rearCarIllustration == null) {
                return;
            }
            rearCarIllustration.setBounds(80, 62, 140, 110);
        }
        rearCarIllustration.draw(canvas);
    }

    private void drawSpeedLimitSign(Canvas canvas) {
        drawCircle(canvas, 178, 35, 20, 0xffd6e0ed);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setColor(0xffb87075);
        canvas.drawCircle(178, 35, 20, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(17);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xff263b54);
        canvas.drawText("60", 178, 41, paint);
    }

    private void drawTrafficLight(Canvas canvas) {
        drawRoundedRectangle(canvas, 35, 20, 15, 43, 7, 0xff182437);
        drawCircle(canvas, 42.5f, 30, 4, 0xffdd828c);
        drawCircle(canvas, 42.5f, 41.5f, 4, 0xffefcb7e);
        drawCircle(canvas, 42.5f, 53, 4, 0xff9bc9b3);
    }
}
