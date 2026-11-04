package ru.big.town.restoremode.appearance;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

final class WidgetSurfaceDrawable extends Drawable {
    private final View tile;
    private final View wallpaperView;
    private final AppearanceSettings settings;
    private final WallpaperDrawable blurredWallpaper;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF rectangle = new RectF();
    private final int[] tilePosition = new int[2];
    private final int[] wallpaperPosition = new int[2];
    private final float radius;
    private final float stroke;
    private int alpha = 255;

    WidgetSurfaceDrawable(View tile, View wallpaperView, AppearanceSettings settings, WallpaperDrawable blurredWallpaper) {
        this.tile = tile;
        this.wallpaperView = wallpaperView;
        this.settings = settings;
        this.blurredWallpaper = blurredWallpaper;
        float density = tile.getResources().getDisplayMetrics().density;
        radius = settings.cornerRadiusDp * density;
        stroke = settings.borderDp * density;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rectangle.set(bounds);
        rectangle.inset(stroke / 2, stroke / 2);
        clip.reset();
        clip.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        if (settings.transparencyEnabled && settings.blurEnabled && settings.blurAmount > 0
                && blurredWallpaper != null) {
            tile.getLocationOnScreen(tilePosition);
            wallpaperView.getLocationOnScreen(wallpaperPosition);
            canvas.save();
            canvas.clipPath(clip);
            canvas.translate(wallpaperPosition[0] - tilePosition[0],
                    wallpaperPosition[1] - tilePosition[1]);
            blurredWallpaper.drawInto(canvas, wallpaperView.getWidth(), wallpaperView.getHeight());
            canvas.restore();
        }
        int surfaceAlpha = settings.surfaceAlpha() * alpha / 255;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(settings.backgroundColor);
        paint.setAlpha(surfaceAlpha);
        canvas.drawRoundRect(rectangle, radius, radius, paint);
        if (stroke > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xff373f4a);
            paint.setAlpha(surfaceAlpha);
            canvas.drawRoundRect(rectangle, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
