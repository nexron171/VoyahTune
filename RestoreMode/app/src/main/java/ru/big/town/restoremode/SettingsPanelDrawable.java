package ru.big.town.restoremode;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Joined RecyclerView rows draw one card without horizontal borders between fragments. */
final class SettingsPanelDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int fill, stroke;
    private final boolean first, last;

    SettingsPanelDrawable(float density, int fill, int stroke, boolean first, boolean last) {
        this.density = density;
        this.fill = fill;
        this.stroke = stroke;
        this.first = first;
        this.last = last;
    }

    @Override
    public void draw(Canvas canvas) {
        float radius = 22 * density;
        RectF box = new RectF(getBounds());
        int save = canvas.save();
        canvas.clipRect(box);
        if (!first) {
            box.top -= radius;
        }
        if (!last) {
            box.bottom += radius;
        }
        box.inset(density / 2, density / 2);
        paint.setColor(fill);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setColor(stroke);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        canvas.drawRoundRect(box, radius, radius, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
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
