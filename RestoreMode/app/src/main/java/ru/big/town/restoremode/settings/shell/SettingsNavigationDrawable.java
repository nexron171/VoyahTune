package ru.big.town.restoremode.settings.shell;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Rounded active navigation background with the prototype's inset left accent. */
public final class SettingsNavigationDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public SettingsNavigationDrawable(float density) {
        this.density = density;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        RectF rect = new RectF(getBounds());
        Path clip = new Path();
        clip.addRoundRect(rect, 16 * density, 16 * density, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(0xff273b57);
        canvas.drawRect(rect, paint);
        paint.setColor(0xff80b4ff);
        canvas.drawRect(rect.left, rect.top, rect.left + 3 * density, rect.bottom, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
