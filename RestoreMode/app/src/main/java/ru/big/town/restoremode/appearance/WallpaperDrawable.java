package ru.big.town.restoremode.appearance;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

final class WallpaperDrawable extends Drawable {
    private Bitmap bitmap;
    private Bitmap previous;
    private float progress = 1f;
    private int alpha = 255;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    WallpaperDrawable(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    void show(Bitmap image) {
        previous = null;
        bitmap = image;
        progress = 1f;
        invalidateSelf();
    }

    void transitionTo(Bitmap image) {
        previous = progress < 1f ? snapshot() : bitmap;
        bitmap = image;
        progress = 0f;
        invalidateSelf();
    }

    void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        if (progress == 1f) {
            previous = null;
        }
        invalidateSelf();
    }

    private Bitmap snapshot() {
        Bitmap reference = bitmap != null ? bitmap : previous;
        if (reference == null) {
            return null;
        }
        Bitmap frame = Bitmap.createBitmap(reference.getWidth(), reference.getHeight(), Bitmap.Config.ARGB_8888);
        drawInto(new Canvas(frame), frame.getWidth(), frame.getHeight());
        return frame;
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        drawInto(canvas, getBounds().width(), getBounds().height());
        canvas.restore();
    }

    void drawInto(Canvas canvas, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int saved = canvas.save();
        if (alpha < 255) {
            canvas.saveLayerAlpha(0, 0, width, height, alpha);
        }
        paint.setColor(Color.BLACK);
        paint.setAlpha(255);
        canvas.drawRect(0, 0, width, height, paint);
        if (previous != null) {
            drawCover(canvas, previous, width, height, paint);
        }
        paint.setAlpha(Math.round(255 * progress));
        if (bitmap != null) {
            drawCover(canvas, bitmap, width, height, paint);
        } else {
            canvas.drawRect(0, 0, width, height, paint);
        }
        canvas.restoreToCount(saved);
    }

    static void drawCover(Canvas canvas, Bitmap bitmap, int width, int height, Paint paint) {
        float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float left = (width - bitmap.getWidth() * scale) / 2;
        float top = (height - bitmap.getHeight() * scale) / 2;
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + bitmap.getWidth() * scale, top + bitmap.getHeight() * scale),
                paint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
