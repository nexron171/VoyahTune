package ru.big.town.restoremode.settings.ui.preview;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

/** Abstract display diagrams, never simulated telemetry or application artwork. */
public final class SettingsPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    private float fraction = .5f;

    public SettingsPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SettingsPreviewView);
        kind = a.getInt(R.styleable.SettingsPreviewView_settingsPreviewKind, 0);
        a.recycle();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    public void setSplit(SplitStore.Preset preset) {
        fraction = SplitStore.leftFraction(preset);
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(
                kind == 1
                        ? "Разделение экрана: слева " + Math.round(fraction * 100) + "%"
                        : kind == 2 ? "Схема системного дока" : "Схема полноэкранного приложения");
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(SettingsDesign.dp(this, 320), widthSpec);
        setMeasuredDimension(width, resolveSize(Math.round(width * 720f / 1920f), heightSpec));
    }

    private void box(Canvas canvas, float l, float t, float r, float b, int color, boolean stroke) {
        paint.setColor(color);
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(l, t, r, b, 5, 5, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == 1) {
            float density = getResources().getDisplayMetrics().density,
                    w = getWidth() / density,
                    h = getHeight() / density;
            canvas.save();
            canvas.scale(density, density);
            rounded(canvas, .5f, .5f, w - .5f, h - .5f, 14, 0xff111b28, false);
            rounded(canvas, .5f, .5f, w - .5f, h - .5f, 14, 0xff425873, true);
            float available = w - 30, left = available * fraction;
            wireframe(canvas, 11, 11, 11 + left, h - 11);
            wireframe(canvas, 19 + left, 11, w - 11, h - 11);
            canvas.restore();
            return;
        }
        // Fit, never stretch: the display silhouette always keeps 1920:720 proportions.
        float scale = Math.min(getWidth() / 384f, getHeight() / 144f);
        canvas.save();
        canvas.translate((getWidth() - 384 * scale) / 2, (getHeight() - 144 * scale) / 2);
        canvas.scale(scale, scale);
        box(canvas, 1, 1, 383, 143, 0xff1c2531, false);
        box(canvas, 1, 1, 383, 143, 0xff384457, true);
        pane(canvas, kind == 2 ? 54 : 12, 12, 372, 132);
        if (kind == 2) {
            for (int i = 0; i < 4; i++) {
                box(canvas, 16, 17 + i * 29, 40, 39 + i * 29, 0xff607795, true);
            }
        }
        canvas.restore();
    }

    private void rounded(
            Canvas canvas,
            float l,
            float t,
            float r,
            float b,
            float radius,
            int color,
            boolean stroke) {
        paint.setColor(color);
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(1);
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    }

    private void wireframe(Canvas canvas, float l, float t, float r, float b) {
        rounded(canvas, l, t, r, b, 8, 0xff243245, false);
        l += 14;
        r -= 14;
        t += 14;
        b -= 14;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff66758e);
        for (int i = 0; i < 3; i++) {
            canvas.drawCircle(l + 2.5f + i * 10, t + 2.5f, 2.5f, paint);
        }
        paint.setColor(0xff465570);
        canvas.drawRect(l, t + 21, r, t + 22, paint);
        t += 34;
        float side = (r - l) * .16f, height = Math.max(0, b - t - 18), unit = height / 4;
        rounded(canvas, l, t, l + side, b, 4, 0xff344359, false);
        l += side + 12;
        rounded(canvas, l, t, r, t + unit, 4, 0xff35455c, false);
        rounded(canvas, l, t + unit + 9, r, t + unit * 3 + 9, 4, 0xff35455c, false);
        rounded(canvas, l, t + unit * 3 + 18, l + (r - l) * .65f, b, 4, 0xff35455c, false);
    }

    private void pane(Canvas canvas, float l, float t, float r, float b) {
        box(canvas, l, t, r, b, 0xff52657f, true);
        box(canvas, l + 9, t + 10, Math.min(r - 9, l + (r - l) * .6f), t + 15, 0xff7188a6, false);
        box(canvas, l + 9, t + 25, r - 9, b - 10, 0xff2b394c, false);
    }
}
