package ru.big.town.restoremode;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/** Glass sphere with liquid colour folds, driven by microphone amplitude. */
final class VoiceOrbView extends View {
    private static final float HALO_RADIUS = 1.35f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petal = new Path();
    private final Path sphere = new Path();
    private final ColorMatrix successTint = new ColorMatrix();
    private final float[] tintValues = new float[20];
    private final Shader glass = radial(-.18f, -.25f, 1.35f,
            new int[]{0xb51b2140, 0xe50a1024, 0xc2182843}, new float[]{0, .72f, 1});
    private final Shader rim = new SweepGradient(0, 0,
            new int[]{0x5534e9ed, 0x184566bc, 0x555d9adb, 0x55ff4c87, 0x5534e9ed}, null);
    private final Shader core = radial(0, 0, .48f,
            new int[]{0xffffffff, 0xf5efffff, 0x5591e7ff, 0x0091e7ff}, new float[]{0, .28f, .65f, 1});
    private final Shader redReflection = radial(-.12f, -.86f, 1.3f,
            new int[]{0x85ea235b, 0x25ea235b, 0x00ea235b}, new float[]{0, .52f, 1});
    private final Shader cyanReflection = radial(.58f, .70f, 1.1f,
            new int[]{0x703ce9f2, 0x193ce9f2, 0x003ce9f2}, new float[]{0, .55f, 1});
    private final float[] petalAngles = {40, 205, 285, 120};
    private final Shader[] petals = {
            petalShader(0xffff4389), petalShader(0xff9b75ff),
            petalShader(0xff3fafff), petalShader(0xff42ffe6)
    };
    private final Shader[] halos = {
            haloShader(0xffff4389), haloShader(0xff9b75ff),
            haloShader(0xff3fafff), haloShader(0xff42ffe6)
    };
    private final Shader[] innerReflections = {
            reflectionShader(0xffff4389), reflectionShader(0xff9b75ff),
            reflectionShader(0xff3fafff), reflectionShader(0xff42ffe6)
    };
    private final Shader redPetal = petalShader(0xffff527b);
    private final Shader redHalo = haloShader(0xffff527b);
    private final Shader redInnerReflection = reflectionShader(0xffff527b);
    private float target, envelope, motion, phase, petalRotation;
    private boolean error;
    private boolean recognized;
    private float successAmount;
    private long lastFrame;

    VoiceOrbView(Context context) { super(context); setContentDescription("Голосовой помощник"); }
    void level(float value) { target = Math.max(0, Math.min(1, value)); }
    void state(boolean listening, boolean error) {
        this.error = error;
        recognized = false;
        successAmount = 0;
        paint.setColorFilter(null);
        if (!listening) target = 0;
        invalidate();
    }
    void recognized() {
        recognized = true;
        error = false;
        target = 0;
        invalidate();
    }
    private void updateSuccessTint(float dt) {
        if (!recognized || successAmount >= 1) return;
        successAmount = Math.min(1, successAmount + dt / .85f);
        float blend = successAmount * successAmount * (3 - 2 * successAmount);
        // Preserve luminous white highlights while gradually tinting all layers mint/turquoise.
        for (int row = 0; row < 3; row++) {
            float tint = row == 0 ? .45f : row == 1 ? 1f : .84f;
            for (int col = 0; col < 3; col++) {
                float luminance = col == 0 ? .213f : col == 1 ? .715f : .072f;
                tintValues[row * 5 + col] = (row == col ? 1 - blend : 0) + blend * tint * luminance;
            }
        }
        tintValues[18] = 1;
        successTint.set(tintValues);
        paint.setColorFilter(new ColorMatrixColorFilter(successTint));
    }
    private static Shader radial(float x, float y, float r, int[] colors, float[] stops) {
        return new RadialGradient(x, y, r, colors, stops, Shader.TileMode.CLAMP);
    }
    private static Shader petalShader(int color) {
        int rgb = color & 0x00ffffff;
        return radial(-.05f, -.30f, .82f,
                new int[]{0xd0ffffff, 0xc0000000 | rgb, 0x18000000 | rgb}, new float[]{0, .43f, 1});
    }
    private static Shader haloShader(int color) {
        int rgb = color & 0x00ffffff;
        // Broad overlapping falloff, like a heavily blurred coloured shadow.
        return radial(-.03f, -.38f, HALO_RADIUS,
                new int[]{0xc0000000 | rgb, 0x94000000 | rgb, 0x50000000 | rgb,
                        0x14000000 | rgb, rgb}, new float[]{0, .25f, .5f, .75f, 1});
    }
    private static Shader reflectionShader(int color) {
        int rgb = color & 0x00ffffff;
        Shader light = radial(0, -.86f, 1.05f,
                new int[]{0xb0000000 | rgb, 0x65000000 | rgb, rgb}, new float[]{0, .45f, 1});
        Shader edge = radial(0, 0, 1,
                new int[]{0x00ffffff, 0x00ffffff, 0x70ffffff, 0xccffffff, 0x18ffffff},
                new float[]{0, .64f, .82f, .94f, 1});
        // Keep reflected light in a soft band along the inside of the glass shell.
        return new ComposeShader(light, edge, BlendMode.DST_IN);
    }
    private void transformPetal(Canvas canvas, int i) {
        float flow = (float) Math.sin(phase + i * 1.4f);
        float fold = (float) Math.cos(phase * .83f + i * 1.8f);
        float curl = (float) Math.sin(phase * 1.37f + i * 2.3f);
        float eddy = (float) Math.cos(phase * 1.71f - i * 1.1f);
        float bend = .12f + motion * .34f;
        canvas.rotate(petalAngles[i] + petalRotation + flow * (15 + motion * 24));
        canvas.translate(curl * bend * .16f, eddy * bend * .12f);
        canvas.skew(curl * (.08f + motion * .22f), fold * (.04f + motion * .10f));
        canvas.scale(.85f + fold * (.12f + motion * .23f), .94f + flow * (.06f + motion * .14f));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? .016f : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        updateSuccessTint(dt);
        envelope += (target - envelope) * (1 - (float) Math.exp(-dt * (target > envelope ? 9 : 4)));
        float activity = Math.min(1, envelope * 1.6f);
        motion += (activity - motion) * (1 - (float) Math.exp(-dt * (activity > motion ? 5 : 1.4)));
        // Integrate both currents and rotation so voice changes never jump their phase.
        phase += dt * (.7f + motion * 2.6f);
        petalRotation = (petalRotation + dt * (18 + motion * 70)) % 360;
        // Extra layout space belongs to the halo, not to a larger sphere.
        float baseRadius = Math.min(Math.min(getWidth(), getHeight()) * .29f,
                87 * getResources().getDisplayMetrics().density);
        float radius = baseRadius * (1 + envelope * .18f);
        float pixel = getResources().getDisplayMetrics().density / radius;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(radius, radius);
        paint.setStyle(Paint.Style.FILL); paint.setBlendMode(null);
        // Follow each fold's direction, but keep the broad shadow round and diffuse.
        // Stretching the glow with the liquid would produce distinct bright lobes outside.
        canvas.save();
        canvas.rotate(-24 + (float) Math.sin(phase * .42f) * 18);
        paint.setBlendMode(BlendMode.SCREEN);
        for (int i = 0; i < halos.length; i++) {
            canvas.save();
            float flow = (float) Math.sin(phase + i * 1.4f);
            canvas.rotate(petalAngles[i] + petalRotation + flow * (15 + motion * 24));
            paint.setShader(error ? redHalo : halos[i]);
            paint.setAlpha((int) (205 + motion * 50));
            canvas.drawCircle(-.03f, -.38f, HALO_RADIUS, paint);
            canvas.restore();
        }
        canvas.restore();
        paint.setBlendMode(null);
        paint.setShader(glass); paint.setAlpha(255);
        canvas.drawCircle(0, 0, 1, paint);

        sphere.reset(); sphere.addCircle(0, 0, .997f, Path.Direction.CW);
        canvas.save(); canvas.clipPath(sphere);
        paint.setBlendMode(BlendMode.SCREEN);
        paint.setShader(redReflection); paint.setAlpha(230);
        canvas.drawCircle(0, 0, 1, paint);
        paint.setShader(error ? redReflection : cyanReflection);
        canvas.drawCircle(0, 0, 1, paint);
        // Petals fold inside a stable round shell instead of rotating the entire silhouette.
        canvas.rotate(-24 + (float) Math.sin(phase * .42f) * 18);
        paint.setBlendMode(BlendMode.SCREEN);
        for (int i = 0; i < petals.length; i++) {
            float flow = (float) Math.sin(phase + i * 1.4f);
            float fold = (float) Math.cos(phase * .83f + i * 1.8f);
            float curl = (float) Math.sin(phase * 1.37f + i * 2.3f);
            float eddy = (float) Math.cos(phase * 1.71f - i * 1.1f);
            float bend = .12f + motion * .34f;
            canvas.save();
            // Independent stretching, shear and moving control points make each fold flow
            // through its neighbours rather than spin as a rigid flower petal.
            transformPetal(canvas, i);
            petal.reset();
            petal.moveTo(-.15f, .09f);
            petal.cubicTo(-.55f + fold * bend, -.08f + curl * bend * .35f,
                    -.80f + eddy * bend * .4f, -.66f + flow * bend,
                    -.30f + curl * bend * .65f, -.78f + fold * bend * .28f);
            petal.cubicTo(.08f + fold * bend, -.96f + eddy * bend * .45f,
                    .72f + curl * bend * .55f, -.65f + fold * bend,
                    .43f + flow * bend * .55f, -.23f + eddy * bend * .3f);
            petal.cubicTo(.29f - eddy * bend * .6f, .02f + flow * bend,
                    -.01f + curl * bend * .25f, .19f, -.15f, .09f);
            paint.setShader(error ? redPetal : petals[i]);
            paint.setAlpha(i == 2 ? 185 : 225);
            canvas.drawPath(petal, paint);
            canvas.restore();
        }
        // A soft central bloom joins the overlapping coloured folds.
        paint.setShader(core); paint.setAlpha((int) (225 + envelope * 30));
        canvas.drawCircle(0, 0, .48f, paint);
        paint.setShader(null);
        for (int i = 0; i < 22; i++) {
            float seed = i * 2.399963f;
            float orbit = .22f + .62f * ((i * 13 % 23) / 23f);
            float angle = seed + phase * (.48f + (i % 5) * .035f);
            float x = (float) Math.cos(angle) * orbit;
            float y = (float) Math.sin(angle) * orbit * .8f;
            float shimmer = (float) Math.pow(.5 + .5 * Math.sin(phase * 1.8f + seed), 8);
            float dot = pixel * (.45f + shimmer * .4f);
            paint.setColor(error ? 0xffffb9c6 : 0xffccf9ff);
            paint.setAlpha((int) (10 + shimmer * 22));
            canvas.drawCircle(x, y, dot * 2.4f, paint);
            paint.setAlpha((int) (35 + shimmer * 135));
            canvas.drawCircle(x, y, dot, paint);
            if (shimmer > .86f && i % 4 == 0) {
                paint.setStrokeWidth(pixel * .45f);
                canvas.drawLine(x - dot * 2.2f, y, x + dot * 2.2f, y, paint);
                canvas.drawLine(x, y - dot * 2.2f, x, y + dot * 2.2f, paint);
            }
        }
        // Reflections turn with the neighbouring coloured folds, including over their edges.
        for (int i = 0; i < innerReflections.length; i++) {
            canvas.save();
            float flow = (float) Math.sin(phase + i * 1.4f);
            canvas.rotate(petalAngles[i] + petalRotation + flow * (15 + motion * 24));
            paint.setShader(error ? redInnerReflection : innerReflections[i]);
            paint.setAlpha((int) (150 + motion * 35));
            canvas.drawCircle(0, 0, 1, paint);
            canvas.restore();
        }
        canvas.restore();
        paint.setBlendMode(null); paint.setShader(error ? redPetal : rim);
        paint.setAlpha(150); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(pixel);
        canvas.drawCircle(0, 0, .993f, paint);
        paint.setShader(null);
        canvas.restore();
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
