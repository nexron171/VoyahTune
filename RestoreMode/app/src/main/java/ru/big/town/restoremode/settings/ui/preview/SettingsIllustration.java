package ru.big.town.restoremode.settings.ui.preview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Decorative vectors and a live layout diagram. No simulated vehicle or microphone readings. */
public final class SettingsIllustration extends View
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;

    public SettingsIllustration(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences("DrivePreferences", 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        invalidate();
    }

    private void rect(Canvas c, float x, float y, float w, float h, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint);
    }

    private void line(Canvas c, float x, float y, float x2, float y2, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        c.drawLine(x, y, x2, y2, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.scale(density, density);
        float w = getWidth() / density, h = getHeight() / density;
        String kind = String.valueOf(getTag());
        if (kind.equals("settings.wave")) {
            int[] heights = {18, 32, 48, 24, 62, 76, 42, 26, 64, 50, 72, 34, 54, 22, 40};
            for (int i = 0; i < heights.length; i++) {
                rect(
                        canvas,
                        (w - 189) / 2 + i * 13,
                        (h - heights[i]) / 2,
                        7,
                        heights[i],
                        4,
                        0xff9ac4ff);
            }
        } else if (kind.equals("settings.grid-columns")) {
            rect(canvas, 0, 0, w, h, 14, 0xff141d2a);
            int count =
                    prefs.getBoolean("fullscreenGrid", false)
                            ? prefs.getInt("fullscreenGridColumns", 8)
                            : 0;
            int spacing = prefs.getInt("tileSpacingDp", 4);
            float gap = 4 + spacing / 2f, cw = (w - 28 - gap * 11) / 12;
            for (int i = 0; i < 12; i++) {
                float ch = i < count ? 65 : 44;
                rect(
                        canvas,
                        14 + i * (cw + gap),
                        h - 14 - ch,
                        cw,
                        ch,
                        6,
                        i < count ? 0xff668ebe : 0xff394d69);
            }
        } else if (kind.equals("settings.keyboard")) {
            String letters =
                    "ru".equals(prefs.getString("keyboardMode", "off"))
                            ? "ЙЦУКЕНГШЩЗ"
                            : "QWERTYUIOP";
            float cw = (w - 45) / 10;
            for (int i = 0; i < 10; i++) {
                rect(canvas, i * (cw + 5), 0, cw, h, 6, 0xff394b63);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(16);
                paint.setColor(0xfff3f5fa);
                canvas.drawText(
                        letters.substring(i, i + 1),
                        i * (cw + 5) + cw / 2,
                        (h - paint.ascent() - paint.descent()) / 2,
                        paint);
            }
        } else if (kind.equals("settings.road")) {
            canvas.scale(w / 220, h / 115);
            line(canvas, 35, 110, 90, 5, 0xff587498, 3);
            line(canvas, 185, 110, 130, 5, 0xff587498, 3);
            for (int i = 0; i < 3; i++) {
                line(canvas, 110, 105 - i * 36, 110, 83 - i * 33, 0xffa1bddb, 2);
            }
            rect(canvas, 99, 70, 22, 33, 7, 0xffa8ccff);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffd6e0ed);
            canvas.drawCircle(178, 35, 20, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
            paint.setColor(0xffb87075);
            canvas.drawCircle(178, 35, 20, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(17);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xff263b54);
            canvas.drawText("60", 178, 41, paint);
            rect(canvas, 35, 20, 15, 43, 7, 0xff182437);
            paint.setColor(0xff9bc9b3);
            canvas.drawCircle(42.5f, 53, 4, paint);
        }
        canvas.restore();
    }
}
