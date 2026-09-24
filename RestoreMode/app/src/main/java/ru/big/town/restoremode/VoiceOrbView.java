package ru.big.town.restoremode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/** Procedural luminous orb. Its radius follows the real microphone RMS, with attack/release easing. */
final class VoiceOrbView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int[] colors = {0xff6deaff, 0xff537dff, 0xffab67ff, 0xffff71cd, 0xff6deaff};
    private float target, envelope;
    private boolean listening, error;
    private long lastFrame;
    private Shader glow, surface;

    VoiceOrbView(Context context) { super(context); setContentDescription("Голосовой помощник"); }
    void level(float value) { target = value; }
    void state(boolean listening, boolean error) {
        this.listening = listening; this.error = error;
        if (!listening) target = 0;
        invalidate();
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float r = Math.min(w, h) * .24f;
        glow = new RadialGradient(0, 0, r * 1.9f, new int[]{0x885474ff, 0x304f8cff, 0x004f8cff},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
        surface = new SweepGradient(0, 0, colors, null);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? .016f : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        envelope += (target - envelope) * (1 - (float) Math.exp(-dt * (target > envelope ? 16 : 7)));
        float t = now / 1000f;
        float radius = Math.min(getWidth(), getHeight()) * .24f * (1 + envelope * .42f);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        paint.setShader(glow); paint.setAlpha(220); paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, radius * 1.9f, paint);
        canvas.rotate(t * 24);
        paint.setShader(error ? null : surface);
        paint.setColor(error ? 0xffff6d88 : Color.WHITE);
        for (int layer = 0; layer < 4; layer++) {
            path.reset();
            for (int i = 0; i <= 120; i++) {
                double angle = i * Math.PI * 2 / 120;
                float deformation = (float) (Math.sin(angle * 3 + t * 1.7 + layer) * .075
                        + Math.sin(angle * 5 - t * 1.3 + layer) * (.025 + envelope * .035));
                float r = radius * (1 - layer * .075f + deformation);
                float x = (float) Math.cos(angle) * r, y = (float) Math.sin(angle) * r;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            paint.setAlpha(95 + layer * 25);
            canvas.drawPath(path, paint);
        }
        paint.setShader(null); paint.setColor(0x99ffffff); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        canvas.drawPath(path, paint);
        canvas.restore();
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
