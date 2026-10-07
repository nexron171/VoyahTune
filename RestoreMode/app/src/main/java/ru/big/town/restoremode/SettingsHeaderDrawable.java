package ru.big.town.restoremode;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Surface color with an accelerating opacity fade; no backdrop capture or blur. */
final class SettingsHeaderDrawable extends Drawable {
    private static final int FADE_LEAD_IN_DP = 12;
    private final View header;
    private final Paint paint = new Paint();
    private int shaderHeight = -1;

    SettingsHeaderDrawable(View header) { this.header = header; }

    private int fadeHeight() {
        View title = header.findViewById(R.id.sectionTitle);
        int bottom = ((View) title.getParent()).getTop() + title.getBottom();
        int leadIn = SettingsDesign.dp(header, FADE_LEAD_IN_DP);
        return Math.max(1, bottom > 0 ? bottom + leadIn
                : getBounds().height() - header.getPaddingBottom() + leadIn);
    }
    @Override protected void onBoundsChange(Rect bounds) { shaderHeight = -1; }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int height = fadeHeight();
        if (shaderHeight != height) {
            float[] stops = new float[33];
            int[] colors = new int[stops.length];
            int color = SettingsDesign.color(header, R.color.settings_background) & 0x00ffffff;
            for (int i = 0; i < stops.length; i++) {
                float progress = (float) i / (stops.length - 1);
                stops[i] = progress;
                colors[i] = (Math.round(255 * (1 - progress * progress * progress)) << 24) | color;
            }
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + height,
                    colors, stops, Shader.TileMode.CLAMP));
            shaderHeight = height;
        }
        canvas.drawRect(bounds, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
