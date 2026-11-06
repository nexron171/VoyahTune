package ru.big.town.restoremode.widgets.fuel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.FuelFlapWidgetProtocol;
import ru.big.town.restoremode.R;

public final class FuelFlapWidgetView extends View {
    private static final int INK = 0xfff0f8f5;
    private static final int SECONDARY = 0xffb6c9c3;
    private static final int AMBER = 0xffffc66a;
    private static final int MINT = 0xff75f6c8;
    private static final String OPEN_CAPTION = "Открыть лючок";
    private static final String IDLE = "Нажмите для открытия";

    private final Path chamber = new Path();
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artwork = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect glyph = new Rect();
    private final RectF destination = new RectF();
    private final Bitmap glass, face, digits;
    private final PorterDuffColorFilter inkTint =
            new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter reserveTint =
            new PorterDuffColorFilter(AMBER, PorterDuff.Mode.SRC_IN);
    private final Typeface regular;
    private Shader liquid;
    private float percent = -1;
    private String refillText;
    private String remainingText;
    private String volumeDescription;
    private String statusLine;
    private boolean canOpen;
    private AlertDialog confirmation;

    public FuelFlapWidgetView(Context context, Runnable open) {
        super(context);
        regular = getResources().getFont(R.font.settings_arimo_regular);
        glass = artwork(R.drawable.fuel_gauge_glass);
        face = artwork(R.drawable.fuel_gauge_face);
        digits = artwork(R.drawable.fuel_gauge_digits);
        setBackgroundResource(R.drawable.card_dark_ripple);
        setFocusable(true);
        chamber.moveTo(32, 136);
        chamber.lineTo(32, 81);
        chamber.quadTo(32, 77, 37, 72);
        chamber.lineTo(55, 54);
        chamber.quadTo(59, 50, 65, 50);
        chamber.lineTo(147, 50);
        chamber.quadTo(153, 50, 157, 55);
        chamber.lineTo(177, 77);
        chamber.quadTo(181, 81, 181, 88);
        chamber.lineTo(181, 136);
        chamber.quadTo(181, 150, 167, 150);
        chamber.lineTo(46, 150);
        chamber.quadTo(32, 150, 32, 136);
        chamber.close();
        setOnClickListener(
                view -> {
                    if (canOpen && confirmation == null) confirmOpening(open);
                });
        update(Bundle.EMPTY);
    }

    private void confirmOpening(Runnable open) {
        confirmation =
                new MaterialAlertDialogBuilder(getContext())
                        .setTitle("Открыть лючок бензобака?")
                        .setMessage(volumeDescription + ". Открытие доступно только в P.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton(
                                "Открыть",
                                (dialog, which) -> {
                                    if (canOpen && confirmation != null && confirmation.isShowing())
                                        open.run();
                                })
                        .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    public void dismissConfirmation() {
        if (confirmation != null) confirmation.dismiss();
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissConfirmation();
        super.onDetachedFromWindow();
    }

    public void update(Bundle state) {
        int refill = state.getInt(FuelFlapWidgetProtocol.REFILL_LITERS, -1);
        int capacity = state.getInt(FuelFlapWidgetProtocol.CAPACITY_LITERS, -1);
        float reading = state.getFloat(FuelFlapWidgetProtocol.REMAINING_PERCENT, -1);
        boolean hasRefill = refill >= 0 && refill <= 200;
        boolean hasLevel =
                hasRefill
                        && capacity > 0
                        && refill <= capacity
                        && capacity <= 200
                        && reading >= 0
                        && reading <= 100;
        percent = hasLevel ? reading : -1;
        boolean reserve = percent <= 5;
        liquid =
                percent > 0
                        ? gradient(
                                0,
                                150.5f - percent,
                                0,
                                151.5f,
                                reserve ? 0xffb28a4a : 0xff51ac8c,
                                reserve ? 0xff985a22 : 0xff116d52)
                        : null;
        refillText = hasRefill ? refill + " л" : "—";
        remainingText = hasLevel ? (capacity - refill) + " л" : "—";
        volumeDescription = hasRefill ? "Можно заправить ≈ " + refillText : "Нет данных о топливе";
        if (hasLevel) volumeDescription += ". В баке ≈ " + remainingText;
        String status = state.getString(FuelFlapWidgetProtocol.MESSAGE, IDLE);
        canOpen = state.getBoolean(FuelFlapWidgetProtocol.CAN_OPEN, false);
        if (!canOpen) dismissConfirmation();
        if (IDLE.equals(status)) {
            statusLine = canOpen ? OPEN_CAPTION : "Нет данных";
        } else if ("Переведите селектор в P".equals(status)) {
            // Older Native builds send the longer parking instruction.
            statusLine = "Переведите в P";
        } else {
            statusLine = status;
        }
        setContentDescription("Бензобак. " + volumeDescription + ". " + statusLine);
        setTooltipText(statusLine);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        fitCanvas(canvas, 420, 194);
        canvas.drawBitmap(glass, 0, 0, artwork);
        drawFuel(canvas);
        canvas.drawBitmap(face, 0, 0, artwork);
        drawLiters(canvas, refillText, 82, false);
        drawLiters(canvas, remainingText, 163, percent >= 0 && percent <= 5);
        drawStatus(canvas, 32, 182, 356, 18);
        canvas.restore();
    }

    private void fitCanvas(Canvas canvas, int width, int height) {
        float scale = Math.min(getWidth() / (float) width, getHeight() / (float) height);
        canvas.translate((getWidth() - width * scale) / 2, (getHeight() - height * scale) / 2);
        canvas.scale(scale, scale);
        artwork.setFilterBitmap(scale != 1f);
    }

    private void drawStatus(Canvas canvas, int x, int baseline, int width, int fontSize) {
        boolean action = canOpen && OPEN_CAPTION.equals(statusLine);
        String label = action ? statusLine + " ›" : statusLine;
        paint.setTypeface(regular);
        paint.setTextSize(fontSize);
        String visible =
                TextUtils.ellipsize(label, paint, width, TextUtils.TruncateAt.END).toString();
        text(canvas, visible, x, baseline, fontSize, regular, action ? INK : SECONDARY);
    }

    private Bitmap artwork(int resource) {
        return ((BitmapDrawable) getContext().getDrawable(resource)).getBitmap();
    }

    private void drawFuel(Canvas canvas) {
        canvas.save();
        canvas.clipPath(chamber);
        if (liquid != null) {
            float level = 150 - percent;
            fill(liquid);
            canvas.drawRect(31, level, 183, 151, paint);
            stroke(percent <= 5 ? AMBER : MINT, 2);
            canvas.drawLine(32, level + 1.5f, 181, level + 1.5f, paint);
        }
        canvas.restore();
        if (percent < 0) text(canvas, "—", 91, 114, 32, regular, SECONDARY);
    }

    // The instrument uses tabular figures and a unit cut from the approved dial artwork.
    private void drawLiters(Canvas canvas, String value, int baseline, boolean reserve) {
        if (value.equals("—")) {
            text(canvas, value, 210, baseline, 38, regular, SECONDARY);
            return;
        }
        artwork.setColorFilter(reserve ? reserveTint : inkTint);
        int x = 210;
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (symbol == ' ') {
                x += 11;
                continue;
            }
            int sourceX = (symbol == 'л' ? 10 : symbol - '0') * 32;
            glyph.set(sourceX, 0, sourceX + 32, 48);
            destination.set(x, baseline - 38, x + 32, baseline + 10);
            canvas.drawBitmap(digits, glyph, destination, artwork);
            x += 21;
        }
        artwork.setColorFilter(null);
    }

    private static Shader gradient(float x1, float y1, float x2, float y2, int start, int end) {
        return new LinearGradient(x1, y1, x2, y2, start, end, Shader.TileMode.CLAMP);
    }

    private void fill(Shader shader) {
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
    }

    private void stroke(int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }

    private void text(
            Canvas canvas, String value, int x, int y, int size, Typeface face, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(face);
        paint.setColor(color);
        paint.setTextSize(size);
        canvas.drawText(value, x, y, paint);
    }
}
