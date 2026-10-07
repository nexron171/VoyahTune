package ru.big.town.restoremode.settings.ui.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.Switch;

import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Native switch semantics with the 58 × 32 visual track from the approved design. */
public final class SettingsToggle extends Switch {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SettingsToggle(Context context) {
        this(context, null);
    }

    public SettingsToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
        setShowText(false);
    }

    @Override
    public void onMeasure(int widthSpec, int heightSpec) {
        if (getText().length() != 0) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        setMeasuredDimension(
                resolveSize(SettingsDesign.dp(this, 58), widthSpec),
                resolveSize(SettingsDesign.dp(this, 32), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getText().length() != 0) {
            super.onDraw(canvas);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.translate((getWidth() - 58 * d) / 2, (getHeight() - 32 * d) / 2);
        canvas.scale(d, d);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isChecked() ? 0xffa5c8ff : 0xff4a5566);
        canvas.drawRoundRect(0, 0, 58, 32, 16, 16, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(isChecked() ? 0xffa5c8ff : 0xff647085);
        canvas.drawRoundRect(.5f, .5f, 57.5f, 31.5f, 16, 16, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isChecked() ? 0xff233a5a : 0xffc4cfdf);
        canvas.drawCircle(isChecked() ? 42 : 16, 16, 12, paint);
        canvas.restore();
    }
}
