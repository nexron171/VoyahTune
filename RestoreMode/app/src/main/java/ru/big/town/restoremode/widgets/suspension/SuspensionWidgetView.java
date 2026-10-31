package ru.big.town.restoremode.widgets.suspension;

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

import ru.big.town.common.SuspensionWidgetProtocol;
import ru.big.town.restoremode.R;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scales the OEM car animation to the selected dashboard tile size. */
public final class SuspensionWidgetView extends View {
    public interface Selection {
        void select(int level);
    }

    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor();
    private static final android.util.LruCache<Integer, Bitmap> FRAMES =
            new android.util.LruCache<Integer, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private static final String[] LEVELS = {"Самый низкий", "Низкий", "Средний", "Высокий"};
    private static final String[] MODES = {"Посадка", "Sport", "Прежний режим", "Outing"};
    private final Paint contentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Selection selection;
    private final RectF[] buttons = {new RectF(), new RectF(), new RectF(), new RectF()};
    private int suspensionHeight = -1;
    private int movementDirection = -1;
    private int pendingLevel = -1;
    private int animationFrame = 60;
    private int previousHeight = -1;
    private boolean controlsAvailable;
    private boolean compactLayout;
    private boolean longPressConsumed;
    private boolean decodingFrame;
    private String message = "Ожидание Native";
    private final String modeLabel = "Режим + подвеска";
    private String lowestBlockedReason = "Нет данных режима движения";
    private AlertDialog levelDialog;
    private android.widget.ArrayAdapter<String> levelAdapter;
    private Bitmap carBitmap;
    private ValueAnimator animator;
    private float touchX;
    private float touchY;

    public SuspensionWidgetView(Context context, Selection selection) {
        super(context);
        this.selection = selection;
        setBackgroundResource(R.drawable.card_dark_ripple);
        setClickable(true);
        setFocusable(true);
        loadFrame(60);
    }

    public void update(Bundle state) {
        suspensionHeight = state.getInt(SuspensionWidgetProtocol.HEIGHT, -1);
        movementDirection = state.getInt(SuspensionWidgetProtocol.DIRECTION, -1);
        pendingLevel = state.getInt(SuspensionWidgetProtocol.PENDING, -1);
        controlsAvailable = state.getBoolean(SuspensionWidgetProtocol.AVAILABLE, false);
        message = state.getString(SuspensionWidgetProtocol.MESSAGE, "");
        int drive = state.getInt(SuspensionWidgetProtocol.DRIVE, -1);
        lowestBlockedReason =
                state.getString(
                        SuspensionWidgetProtocol.LOWEST_BLOCKED_REASON,
                        drive == 4
                                ? "Удобная посадка недоступна в Outing"
                                : drive < 1 || drive > 6 ? "Нет данных режима движения" : "");
        if (lowestBlockedReason == null) {
            lowestBlockedReason = "";
        }
        if (suspensionHeight >= 1 && suspensionHeight <= 9 && suspensionHeight != previousHeight) {
            int target = frameForHeight(suspensionHeight);
            if (animator != null) {
                animator.cancel();
            }
            if (previousHeight < 0) {
                animationFrame = target;
                loadFrame(animationFrame);
            } else {
                animator = ValueAnimator.ofInt(animationFrame, target);
                animator.setDuration(Math.max(300, Math.abs(animationFrame - target) * 16L));
                animator.addUpdateListener(
                        animation -> {
                            animationFrame = (int) animation.getAnimatedValue();
                            loadFrame(animationFrame);
                        });
                animator.start();
            }
            previousHeight = suspensionHeight;
        }
        setContentDescription(
                "Подвеска: " + levelText() + ". " + message + ". " + lowestBlockedReason);
        if (levelAdapter != null) {
            levelAdapter.notifyDataSetChanged();
        }
        invalidate();
    }

    private boolean canSelect(int level) {
        return controlsAvailable && pendingLevel < 0 && (level != 0 || lowestBlockedReason.isEmpty());
    }

    private static int frameForHeight(int value) {
        switch (value) {
            case 1:
                return 80;
            case 2:
                return 76;
            case 3:
                return 70;
            case 4:
                return 66;
            case 5:
                return 60;
            case 6:
                return 50;
            case 7:
                return 40;
            case 8:
                return 30;
            default:
                return 20;
        }
    }

    private void loadFrame(int requestedFrame) {
        int frameIndex = Math.max(20, Math.min(80, (requestedFrame / 2) * 2));
        Bitmap cachedFrame = FRAMES.get(frameIndex);
        if (cachedFrame != null) {
            carBitmap = cachedFrame;
            invalidate();
            return;
        }
        if (decodingFrame) {
            return;
        }
        decodingFrame = true;
        Context applicationContext = getContext().getApplicationContext();
        DECODER.execute(
                () -> {
                    Bitmap decodedFrame = null;
                    try (InputStream input =
                            applicationContext.getAssets().open("suspension/body_" + frameIndex + ".png")) {
                        decodedFrame = BitmapFactory.decodeStream(input);
                        if (decodedFrame != null) {
                            FRAMES.put(frameIndex, decodedFrame);
                        }
                    } catch (Exception ignored) {
                    }
                    Bitmap loadedFrame = decodedFrame;
                    post(
                            () -> {
                                decodingFrame = false;
                                if (loadedFrame != null) {
                                    carBitmap = loadedFrame;
                                }
                                invalidate();
                                if (loadedFrame != null
                                        && frameIndex != (animationFrame / 2) * 2
                                        && isAttachedToWindow()) {
                                    loadFrame(animationFrame);
                                }
                            });
                });
    }

    private String levelText() {
        if (suspensionHeight < 1 || suspensionHeight > 9) {
            return "Нет данных высоты";
        }
        if (suspensionHeight % 2 == 0) {
            return movementDirection == 1
                    ? "Поднимается…"
                    : movementDirection == 2 ? "Опускается…" : "Регулировка…";
        }
        switch (suspensionHeight) {
            case 1:
                return "Максимальный";
            case 3:
                return "Повышенный";
            case 5:
                return "Средний";
            case 7:
                return "Низкий";
            default:
                return "Самый низкий";
        }
    }

    private void drawText(
            Canvas canvas, String value, float x, float y, float size, int color, Paint.Align align) {
        contentPaint.setColor(color);
        contentPaint.setTextSize(size);
        contentPaint.setTextAlign(align);
        canvas.drawText(value, x, y, contentPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float viewHeight = getHeight();
        float density = getResources().getDisplayMetrics().density;
        compactLayout = width < 350 * density || viewHeight < 230 * density;
        float padding = 12 * density;
        float headingTextSize = compactLayout ? Math.min(18 * density, Math.max(11 * density, width / 17)) : 26 * density;
        contentPaint.setTextSize(headingTextSize);
        String heading =
                android.text.TextUtils.ellipsize(
                                "Подвеска · " + levelText(),
                                new android.text.TextPaint(contentPaint),
                                width - 2 * padding,
                                android.text.TextUtils.TruncateAt.END)
                        .toString();
        drawText(canvas, heading, padding, padding + headingTextSize, headingTextSize, 0xffeeeeee, Paint.Align.LEFT);
        float bottom = viewHeight - padding;
        float buttonHeight = compactLayout ? 28 * density : 50 * density;
        float contentBottom = bottom - buttonHeight - 10 * density;
        if (!compactLayout) {
            String info =
                    pendingLevel >= 0
                            ? "Ожидаем: " + LEVELS[pendingLevel]
                            : !lowestBlockedReason.isEmpty() && controlsAvailable
                                    ? lowestBlockedReason
                                    : message.isEmpty() ? modeLabel : message;
            contentPaint.setTextSize(16 * density);
            CharSequence fit =
                    android.text.TextUtils.ellipsize(
                            info,
                            new android.text.TextPaint(contentPaint),
                            width - 2 * padding,
                            android.text.TextUtils.TruncateAt.END);
            drawText(canvas, fit.toString(), padding, padding + headingTextSize + 24 * density, 16 * density, 0xffaeb7c6, Paint.Align.LEFT);
            float top = padding + headingTextSize + 30 * density;
            float imageBottom = contentBottom;
            if (carBitmap != null && imageBottom > top) {
                float scale =
                        Math.min(
                                (width - 2 * padding) / carBitmap.getWidth(),
                                (imageBottom - top) / carBitmap.getHeight());
                float imageWidth = carBitmap.getWidth() * scale;
                float imageHeight = carBitmap.getHeight() * scale;
                rect.set((width - imageWidth) / 2, top, (width + imageWidth) / 2, top + imageHeight);
                canvas.drawBitmap(carBitmap, null, rect, carPaint);
            }
        }
        for (int buttonIndex = 0; buttonIndex < 4; buttonIndex++) {
            buttons[buttonIndex].setEmpty();
        }
        int count = compactLayout ? 1 : 4;
        float buttonWidth = (width - 2 * padding - (count - 1) * 5 * density) / count;
        for (int buttonIndex = 0; buttonIndex < count; buttonIndex++) {
            RectF buttonBounds = buttons[buttonIndex];
            buttonBounds.set(
                    padding + buttonIndex * (buttonWidth + 5 * density),
                    bottom - buttonHeight,
                    padding + buttonIndex * (buttonWidth + 5 * density) + buttonWidth,
                    bottom);
            boolean selected = !compactLayout && suspensionHeight == new int[] {9, 7, 5, 1}[buttonIndex];
            boolean enabled = compactLayout ? controlsAvailable && pendingLevel < 0 : canSelect(buttonIndex);
            contentPaint.setColor(!enabled ? 0xff383e49 : selected ? 0xff2e80c9 : 0xff445267);
            canvas.drawRoundRect(buttonBounds, 8 * density, 8 * density, contentPaint);
            drawText(
                    canvas,
                    compactLayout ? "Выбрать уровень" : LEVELS[buttonIndex],
                    buttonBounds.centerX(),
                    buttonBounds.top + (compactLayout ? 19 : 21) * density,
                    compactLayout ? 12 * density : Math.min(17 * density, buttonWidth / 8.5f),
                    enabled ? 0xffeeeeee : 0xff858c98,
                    Paint.Align.CENTER);
            if (!compactLayout) {
                drawText(
                        canvas,
                        buttonIndex == 0 && !lowestBlockedReason.isEmpty() ? "Недоступно" : MODES[buttonIndex],
                        buttonBounds.centerX(),
                        buttonBounds.bottom - 9 * density,
                        Math.min(13 * density, buttonWidth / 9),
                        enabled ? 0xffccd6e4 : 0xff858c98,
                        Paint.Align.CENTER);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            longPressConsumed = false;
        }
        touchX = event.getX();
        touchY = event.getY();
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performLongClick() {
        longPressConsumed = true;
        return super.performLongClick();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (longPressConsumed || !controlsAvailable || pendingLevel >= 0) {
            return true;
        }
        if (compactLayout) {
            String[] choices = new String[4];
            for (int buttonIndex = 0; buttonIndex < 4; buttonIndex++) {
                choices[buttonIndex] = LEVELS[buttonIndex] + " · " + MODES[buttonIndex];
            }
            if (levelDialog != null) {
                return true;
            }
            levelAdapter =
                    new android.widget.ArrayAdapter<String>(
                            getContext(), android.R.layout.simple_list_item_1, choices) {
                        @Override
                        public boolean areAllItemsEnabled() {
                            return false;
                        }

                        @Override
                        public boolean isEnabled(int position) {
                            return canSelect(position);
                        }

                        @Override
                        public View getView(
                                int position, View convertView, android.view.ViewGroup parent) {
                            android.widget.TextView row =
                                    (android.widget.TextView)
                                            super.getView(position, convertView, parent);
                            row.setText(
                                    choices[position]
                                            + (position == 0 && !lowestBlockedReason.isEmpty()
                                                    ? "\n" + lowestBlockedReason
                                                    : ""));
                            row.setEnabled(isEnabled(position));
                            row.setAlpha(isEnabled(position) ? 1f : 0.45f);
                            return row;
                        }
                    };
            levelDialog =
                    new AlertDialog.Builder(getContext())
                            .setTitle("Подвеска и режим движения")
                            .setAdapter(
                                    levelAdapter,
                                    (dialog, which) -> {
                                        if (canSelect(which)) {
                                            selection.select(which);
                                        }
                                    })
                            .create();
            levelDialog.setOnDismissListener(
                    dialog -> {
                        levelDialog = null;
                        levelAdapter = null;
                    });
            levelDialog.show();
        } else {
            for (int buttonIndex = 0; buttonIndex < 4; buttonIndex++) {
                if (buttons[buttonIndex].contains(touchX, touchY) && canSelect(buttonIndex)) {
                    selection.select(buttonIndex);
                }
            }
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        if (levelDialog != null) {
            levelDialog.dismiss();
        }
        super.onDetachedFromWindow();
    }
}
