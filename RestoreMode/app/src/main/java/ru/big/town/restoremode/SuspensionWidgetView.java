package ru.big.town.restoremode;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ru.big.town.common.SuspensionWidgetProtocol;

/** Scales the OEM car animation to the selected dashboard tile size. */
final class SuspensionWidgetView extends View {
    interface Selection { void select(int level); }
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor();
    private static final android.util.LruCache<Integer, Bitmap> FRAMES = new android.util.LruCache<Integer, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap value) { return value.getByteCount(); }
    };
    private static final String[] LEVELS = {"Самый низкий", "Низкий", "Средний", "Высокий"};
    private static final String[] MODES = {"Посадка", "Sport", "Прежний режим", "Outing"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Selection selection;
    private final RectF[] buttons = {new RectF(), new RectF(), new RectF(), new RectF()};
    private int height = -1, direction = -1, pending = -1, frame = 60, lastHeight = -1;
    private boolean available, compact, longPressed, decoding;
    private String message = "Ожидание Native", mode = "Режим + подвеска";
    private String lowestBlockedReason = "Нет данных режима движения";
    private AlertDialog levelDialog;
    private android.widget.ArrayAdapter<String> levelAdapter;
    private Bitmap bitmap;
    private ValueAnimator animator;
    private float touchX, touchY;

    SuspensionWidgetView(Context context, Selection selection) {
        super(context); this.selection = selection;
        setBackgroundResource(R.drawable.card_dark_ripple);
        setClickable(true); setFocusable(true);
        loadFrame(60);
    }
    void update(Bundle state) {
        height = state.getInt(SuspensionWidgetProtocol.HEIGHT, -1);
        direction = state.getInt(SuspensionWidgetProtocol.DIRECTION, -1);
        pending = state.getInt(SuspensionWidgetProtocol.PENDING, -1);
        available = state.getBoolean(SuspensionWidgetProtocol.AVAILABLE, false);
        message = state.getString(SuspensionWidgetProtocol.MESSAGE, "");
        int drive = state.getInt(SuspensionWidgetProtocol.DRIVE, -1);
        lowestBlockedReason = state.getString(SuspensionWidgetProtocol.LOWEST_BLOCKED_REASON,
                drive == 4 ? "Удобная посадка недоступна в Outing"
                        : drive < 1 || drive > 6 ? "Нет данных режима движения" : "");
        if (lowestBlockedReason == null) lowestBlockedReason = "";
        if (height >= 1 && height <= 9 && height != lastHeight) {
            int target = frameForHeight(height);
            if (animator != null) animator.cancel();
            if (lastHeight < 0) { frame = target; loadFrame(frame); }
            else {
                animator = ValueAnimator.ofInt(frame, target);
                animator.setDuration(Math.max(300, Math.abs(frame - target) * 16L));
                animator.addUpdateListener(a -> { frame = (int) a.getAnimatedValue(); loadFrame(frame); });
                animator.start();
            }
            lastHeight = height;
        }
        setContentDescription("Подвеска: " + levelText() + ". " + message + ". " + lowestBlockedReason);
        if (levelAdapter != null) levelAdapter.notifyDataSetChanged();
        invalidate();
    }
    private boolean canSelect(int level) {
        return available && pending < 0 && (level != 0 || lowestBlockedReason.isEmpty());
    }
    private static int frameForHeight(int value) {
        switch (value) {
            case 1: return 80; case 2: return 76; case 3: return 70; case 4: return 66;
            case 5: return 60; case 6: return 50; case 7: return 40; case 8: return 30; default: return 20;
        }
    }
    private void loadFrame(int requested) {
        int index = Math.max(20, Math.min(80, (requested / 2) * 2));
        Bitmap cached = FRAMES.get(index);
        if (cached != null) { bitmap = cached; invalidate(); return; }
        if (decoding) return;
        decoding = true;
        Context app = getContext().getApplicationContext();
        DECODER.execute(() -> {
            Bitmap decoded = null;
            try (InputStream in = app.getAssets().open("suspension/body_" + index + ".png")) {
                decoded = BitmapFactory.decodeStream(in);
                if (decoded != null) FRAMES.put(index, decoded);
            } catch (Exception ignored) { }
            Bitmap result = decoded;
            post(() -> {
                decoding = false;
                if (result != null) bitmap = result;
                invalidate();
                if (result != null && index != (frame / 2) * 2 && isAttachedToWindow()) loadFrame(frame);
            });
        });
    }
    private String levelText() {
        if (height < 1 || height > 9) return "Нет данных высоты";
        if (height % 2 == 0) return direction == 1 ? "Поднимается…" : direction == 2 ? "Опускается…" : "Регулировка…";
        switch (height) {
            case 1: return "Максимальный"; case 3: return "Повышенный";
            case 5: return "Средний"; case 7: return "Низкий"; default: return "Самый низкий";
        }
    }
    private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
        paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
        c.drawText(value, x, y, paint);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        compact = w < 350 * d || h < 230 * d;
        float pad = 12 * d, font = compact ? Math.min(18 * d, Math.max(11 * d, w / 17)) : 26 * d;
        paint.setTextSize(font);
        String heading = android.text.TextUtils.ellipsize("Подвеска · " + levelText(),
                new android.text.TextPaint(paint), w - 2 * pad, android.text.TextUtils.TruncateAt.END).toString();
        text(c, heading, pad, pad + font, font, 0xffeeeeee, Paint.Align.LEFT);
        float bottom = h - pad;
        float buttonHeight = compact ? 28 * d : 50 * d;
        float contentBottom = bottom - buttonHeight - 10 * d;
        if (!compact) {
            String info = pending >= 0 ? "Ожидаем: " + LEVELS[pending]
                    : !lowestBlockedReason.isEmpty() && available ? lowestBlockedReason
                    : message.isEmpty() ? mode : message;
            paint.setTextSize(16 * d);
            CharSequence fit = android.text.TextUtils.ellipsize(info, new android.text.TextPaint(paint), w - 2 * pad, android.text.TextUtils.TruncateAt.END);
            text(c, fit.toString(), pad, pad + font + 24 * d, 16 * d, 0xffaeb7c6, Paint.Align.LEFT);
            float top = pad + font + 30 * d, imageBottom = contentBottom;
            if (bitmap != null && imageBottom > top) {
                float scale = Math.min((w - 2 * pad) / bitmap.getWidth(), (imageBottom - top) / bitmap.getHeight());
                float bw = bitmap.getWidth() * scale, bh = bitmap.getHeight() * scale;
                rect.set((w - bw) / 2, top, (w + bw) / 2, top + bh);
                paint.setAlpha(height < 1 ? 100 : 255); c.drawBitmap(bitmap, null, rect, paint); paint.setAlpha(255);
            }
        }
        for (int i = 0; i < 4; i++) buttons[i].setEmpty();
        int count = compact ? 1 : 4;
        float bw = (w - 2 * pad - (count - 1) * 5 * d) / count;
        for (int i = 0; i < count; i++) {
            RectF b = buttons[i]; b.set(pad + i * (bw + 5 * d), bottom - buttonHeight, pad + i * (bw + 5 * d) + bw, bottom);
            boolean selected = !compact && height == new int[]{9,7,5,1}[i];
            boolean enabled = compact ? available && pending < 0 : canSelect(i);
            paint.setColor(!enabled ? 0xff383e49 : selected ? 0xff2e80c9 : 0xff445267);
            c.drawRoundRect(b, 8 * d, 8 * d, paint);
            text(c, compact ? "Выбрать уровень" : LEVELS[i], b.centerX(), b.top + (compact ? 19 : 21) * d,
                    compact ? 12 * d : Math.min(17 * d, bw / 8.5f), enabled ? 0xffeeeeee : 0xff858c98, Paint.Align.CENTER);
            if (!compact) text(c, i == 0 && !lowestBlockedReason.isEmpty() ? "Недоступно" : MODES[i],
                    b.centerX(), b.bottom - 9 * d, Math.min(13 * d, bw / 9), enabled ? 0xffccd6e4 : 0xff858c98, Paint.Align.CENTER);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) longPressed = false;
        touchX = event.getX(); touchY = event.getY();
        return super.onTouchEvent(event);
    }
    @Override public boolean performLongClick() { longPressed = true; return super.performLongClick(); }
    @Override public boolean performClick() {
        super.performClick();
        if (longPressed || !available || pending >= 0) return true;
        if (compact) {
            String[] choices = new String[4];
            for (int i = 0; i < 4; i++) choices[i] = LEVELS[i] + " · " + MODES[i];
            if (levelDialog != null) return true;
            levelAdapter = new android.widget.ArrayAdapter<String>(getContext(),
                    android.R.layout.simple_list_item_1, choices) {
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) { return canSelect(position); }
                @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    android.widget.TextView row = (android.widget.TextView) super.getView(position, convertView, parent);
                    row.setText(choices[position] + (position == 0 && !lowestBlockedReason.isEmpty()
                            ? "\n" + lowestBlockedReason : ""));
                    row.setEnabled(isEnabled(position));
                    row.setAlpha(isEnabled(position) ? 1f : 0.45f);
                    return row;
                }
            };
            levelDialog = new AlertDialog.Builder(getContext()).setTitle("Подвеска и режим движения")
                    .setAdapter(levelAdapter, (dialog, which) -> { if (canSelect(which)) selection.select(which); }).create();
            levelDialog.setOnDismissListener(dialog -> { levelDialog = null; levelAdapter = null; });
            levelDialog.show();
        } else {
            for (int i = 0; i < 4; i++) if (buttons[i].contains(touchX, touchY) && canSelect(i)) selection.select(i);
        }
        return true;
    }
    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        if (levelDialog != null) levelDialog.dismiss();
        super.onDetachedFromWindow();
    }
}
