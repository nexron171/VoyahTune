package ru.big.town.restoremode.widgets.energy;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Prepared off the UI thread; explicit alpha masks also work on hardware canvases. */
final class EnergyCarImage {
    private static final float REFERENCE_HEIGHT = 345f;
    private final Bitmap body, softShadow, contactShadow;
    private final int[] softOffset = new int[2], contactOffset = new int[2];

    EnergyCarImage(Bitmap body) {
        this.body = body;
        float sourceScale = body.getHeight() / REFERENCE_HEIGHT;
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        maskPaint.setMaskFilter(new BlurMaskFilter(12 * sourceScale, BlurMaskFilter.Blur.NORMAL));
        softShadow = body.extractAlpha(maskPaint, softOffset);
        maskPaint.setMaskFilter(new BlurMaskFilter(2 * sourceScale, BlurMaskFilter.Blur.NORMAL));
        contactShadow = body.extractAlpha(maskPaint, contactOffset);
    }

    void draw(Canvas canvas, Paint paint, float centerX, float top, float height) {
        float scale = height / body.getHeight(), left = centerX - body.getWidth() * scale / 2;
        float shadowScale = height / REFERENCE_HEIGHT;
        paint.setColor(0xa6000000);
        drawBitmap(
                canvas,
                paint,
                softShadow,
                left + softOffset[0] * scale,
                top + softOffset[1] * scale + 8 * shadowScale,
                scale);
        paint.setColor(0x73000000);
        drawBitmap(
                canvas,
                paint,
                contactShadow,
                left + contactOffset[0] * scale,
                top + contactOffset[1] * scale + 2 * shadowScale,
                scale);
        paint.setColor(Color.WHITE);
        drawBitmap(canvas, paint, body, left, top, scale);
    }

    private static void drawBitmap(
            Canvas canvas, Paint paint, Bitmap bitmap, float left, float top, float scale) {
        // Explicit destination bounds avoid density-dependent scaling of alpha masks.
        canvas.drawBitmap(
                bitmap,
                null,
                new RectF(
                        left,
                        top,
                        left + bitmap.getWidth() * scale,
                        top + bitmap.getHeight() * scale),
                paint);
    }
}
