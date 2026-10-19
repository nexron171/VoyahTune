package ru.big.town.restoremode.widgets.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.media.NowPlayingClient;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetStyle;

/** Self-drawn current-player card with source selection, album art and transport zones. */
final class MediaWidgetView extends View {
    enum Command {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT
    }

    interface Listener {
        void onCommand(Command command);

        void onOpenPlayer();

        void onChooseSource();
    }

    private static final int ZONE_NONE = 0;
    private static final int ZONE_PREVIOUS = 1;
    private static final int ZONE_PLAY = 2;
    private static final int ZONE_NEXT = 3;
    private static final int ZONE_OPEN = 4;
    private static final int ZONE_SOURCES = 5;

    private static final int ACCENT = 0xff79b5f1;
    private static final int CONTROL_BACKGROUND = 0x332f4c6a;
    private static final int PLACEHOLDER = 0xff263646;
    // Source picker stays neutral so it does not compete with the transport accent color.
    private static final int SOURCE_BACKGROUND = 0xff3c4249;
    private static final int SOURCE_BACKGROUND_PRESSED = 0xff4a515a;
    private static final int SOURCE_BORDER = 0xff626a74;
    private static final int SOURCE_TEXT = 0xffe7e9ec;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF artRect = new RectF();
    private final RectF previousRect = new RectF();
    private final RectF playRect = new RectF();
    private final RectF nextRect = new RectF();
    private final RectF emptySourceRect = new RectF();
    private final RectF sourceRect = new RectF();
    private final Drawable placeholder;
    private final Listener listener;
    private final float density;
    private final float fontScale;
    private final float touchSlop;

    private NowPlayingClient.NowPlaying nowPlaying = new NowPlayingClient.NowPlaying();
    private Bitmap art;
    private float widthDp;
    private float heightDp;
    private float textLeft;
    private float textRight;
    private float titleBaseline;
    private float subtitleBaseline;
    private float titleSize;
    private float subtitleSize;
    private float progressY;
    private boolean compact;
    private boolean tall;
    private boolean poster;
    private int pressedZone;
    private float downX;
    private float downY;

    private final Runnable longPress =
            () -> {
                pressedZone = ZONE_NONE;
                invalidate();
                performLongClick();
            };

    MediaWidgetView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        fontScale = getResources().getDisplayMetrics().scaledDensity / density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        placeholder = context.getDrawable(R.drawable.settings_icon_music);
        setBackgroundResource(R.drawable.layout_category_bg);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
    }

    void update(NowPlayingClient.NowPlaying snapshot, Bitmap albumArt) {
        nowPlaying = snapshot == null ? new NowPlayingClient.NowPlaying() : snapshot;
        art = albumArt;
        updateContentDescription();
        invalidate();
    }

    private void updateContentDescription() {
        if (nowPlaying.isEmpty()) {
            setContentDescription(
                    "Управление музыкой: ничего не играет. Выбрать источник.");
            return;
        }
        String state = nowPlaying.isPlaying() ? "воспроизведение" : "пауза";
        String subtitle = !nowPlaying.artist.isEmpty() ? nowPlaying.artist : nowPlaying.appLabel;
        setContentDescription(
                "Сейчас играет: "
                        + nowPlaying.title
                        + (subtitle.isEmpty() ? "" : ", " + subtitle)
                        + ". Состояние: "
                        + state
                        + ". Кнопки управления музыкой и выбор источника.");
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutContent();
    }

    private void layoutContent() {
        widthDp = getWidth() / density;
        heightDp = getHeight() / density;
        artRect.setEmpty();
        previousRect.setEmpty();
        playRect.setEmpty();
        nextRect.setEmpty();
        emptySourceRect.setEmpty();
        sourceRect.setEmpty();
        if (widthDp <= 0 || heightDp <= 0) {
            return;
        }
        float padding = 8f;
        compact = widthDp < 200f;
        tall = heightDp >= 170f;
        poster = !compact && heightDp >= 300f;

        float buttonWidth = Math.min(widthDp - padding * 2, compact ? 140f : 260f);
        float buttonHeight = compact ? 44f : 56f;
        emptySourceRect.set(
                (widthDp - buttonWidth) / 2,
                (heightDp - buttonHeight) / 2,
                (widthDp + buttonWidth) / 2,
                (heightDp + buttonHeight) / 2);

        if (compact) {
            float size = Math.min(widthDp, heightDp) - padding * 2;
            artRect.set(
                    (widthDp - size) / 2,
                    (heightDp - size) / 2,
                    (widthDp + size) / 2,
                    (heightDp + size) / 2);
            float radius = Math.min(widthDp, heightDp) * .2f;
            playRect.set(
                    widthDp / 2 - radius,
                    heightDp / 2 - radius,
                    widthDp / 2 + radius,
                    heightDp / 2 + radius);
            progressY = heightDp - padding;
            return;
        }

        if (poster) {
            layoutPoster();
            return;
        }

        float artSize = Math.min(heightDp - padding * 2, widthDp * .42f);
        artSize = Math.min(artSize, tall ? 220f : 94f);
        float artTop = tall ? (heightDp - artSize) / 2 : padding;
        artRect.set(padding, artTop, padding + artSize, artTop + artSize);
        textLeft = artRect.right + 14f;
        textRight = widthDp - padding;

        titleSize = tall ? 28f : widthDp < 360f ? 20f : 24f;
        subtitleSize = tall ? 20f : widthDp < 360f ? 16f : 18f;
        float textTop = tall ? artTop : padding;
        titleBaseline = textTop + titleSize;
        subtitleBaseline = titleBaseline + subtitleSize + (tall ? 8f : 4f);

        float controlRadius = tall ? 26f : 18f;
        float gap = tall ? 14f : 8f;
        float total = controlRadius * 6 + gap * 2;
        float left = textLeft + Math.max(0, (textRight - textLeft - total) / 2);
        float centerY =
                tall
                        ? (subtitleBaseline + heightDp - padding - 28f) / 2
                        : heightDp - padding - controlRadius - 4f;
        previousRect.set(
                left,
                centerY - controlRadius,
                left + controlRadius * 2,
                centerY + controlRadius);
        playRect.set(
                previousRect.right + gap,
                centerY - controlRadius,
                previousRect.right + gap + controlRadius * 2,
                centerY + controlRadius);
        nextRect.set(
                playRect.right + gap,
                centerY - controlRadius,
                playRect.right + gap + controlRadius * 2,
                centerY + controlRadius);
        progressY = tall ? heightDp - padding - 14f : heightDp - padding;
    }

    private void layoutPoster() {
        float padding = 14f;
        float sourceHeight = 44f;
        sourceRect.set(padding, padding, widthDp - padding, padding + sourceHeight);

        float controlRadius = Math.min(26f, Math.max(18f, widthDp * .07f));
        float gap = Math.max(14f, controlRadius * .55f);
        float controlsCenterY = heightDp - padding - controlRadius;
        float total = controlRadius * 6 + gap * 2;
        float left = (widthDp - total) / 2f;
        previousRect.set(
                left,
                controlsCenterY - controlRadius,
                left + controlRadius * 2,
                controlsCenterY + controlRadius);
        playRect.set(
                previousRect.right + gap,
                controlsCenterY - controlRadius,
                previousRect.right + gap + controlRadius * 2,
                controlsCenterY + controlRadius);
        nextRect.set(
                playRect.right + gap,
                controlsCenterY - controlRadius,
                playRect.right + gap + controlRadius * 2,
                controlsCenterY + controlRadius);

        float artTop = sourceRect.bottom + 12f;
        float artMaxHeight = controlsCenterY - controlRadius - artTop - 120f;
        float artSize = Math.min(widthDp - padding * 2, Math.max(70f, artMaxHeight));
        float artCap = heightDp >= 440f ? 340f : 230f;
        artSize = Math.min(artSize, artCap);
        float artLeft = (widthDp - artSize) / 2f;
        artRect.set(artLeft, artTop, artLeft + artSize, artTop + artSize);

        textLeft = padding;
        textRight = widthDp - padding;
        titleSize = 26f;
        subtitleSize = 18f;
        titleBaseline = artRect.bottom + 26f;
        subtitleBaseline = titleBaseline + 22f;
        progressY = Math.min(subtitleBaseline + 26f, controlsCenterY - controlRadius - 20f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (widthDp <= 0 || heightDp <= 0) {
            return;
        }
        canvas.save();
        canvas.scale(density, density);

        if (nowPlaying.isEmpty()) {
            drawEmpty(canvas);
            canvas.restore();
            return;
        }
        drawArt(canvas);
        if (poster) {
            drawSourceButton(canvas);
            drawPosterTrackText(canvas);
            drawProgress(canvas);
            drawControls(canvas);
        } else if (compact) {
            drawCompactProgress(canvas);
            drawControls(canvas);
        } else {
            drawTrackText(canvas);
            drawProgress(canvas);
            drawControls(canvas);
        }
        canvas.restore();
    }

    private void drawArt(Canvas canvas) {
        float radius = compact ? 12f : 10f;
        path.reset();
        path.addRoundRect(artRect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        if (art == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PLACEHOLDER);
            canvas.drawRect(artRect, paint);
            if (placeholder != null) {
                float inset = artRect.width() * .27f;
                placeholder.setBounds(
                        Math.round(artRect.left + inset),
                        Math.round(artRect.top + inset),
                        Math.round(artRect.right - inset),
                        Math.round(artRect.bottom - inset));
                placeholder.setAlpha(200);
                placeholder.draw(canvas);
            }
        } else {
            Rect source = new Rect();
            float scale =
                    Math.max(artRect.width() / art.getWidth(), artRect.height() / art.getHeight());
            float cropWidth = artRect.width() / scale;
            float cropHeight = artRect.height() / scale;
            source.set(
                    Math.round((art.getWidth() - cropWidth) / 2),
                    Math.round((art.getHeight() - cropHeight) / 2),
                    Math.round((art.getWidth() + cropWidth) / 2),
                    Math.round((art.getHeight() + cropHeight) / 2));
            paint.setFilterBitmap(true);
            canvas.drawBitmap(art, source, artRect, paint);
            paint.setFilterBitmap(false);
        }
        canvas.restore();
    }

    private void drawEmpty(Canvas canvas) {
        drawActionButton(
                canvas,
                emptySourceRect,
                ZONE_SOURCES,
                compact ? "Источник" : "Выбрать источник",
                false);
    }

    private void drawSourceButton(Canvas canvas) {
        drawActionButton(canvas, sourceRect, ZONE_SOURCES, "Источник", true);
    }

    private void drawActionButton(
            Canvas canvas, RectF rect, int zone, String label, boolean chevron) {
        if (rect.isEmpty()) {
            return;
        }
        float radius = rect.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressedZone == zone ? SOURCE_BACKGROUND_PRESSED : SOURCE_BACKGROUND);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(SOURCE_BORDER);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0f);
        paint.setColor(SOURCE_TEXT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.SANS_SERIF);
        float textSize = Math.min(18f, rect.height() * .38f);
        paint.setTextSize(textSize * fontScale);
        float baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(
                label,
                rect.centerX() - (chevron ? 10f : 0f),
                baseline,
                paint);
        if (chevron) {
            float centerX = rect.right - 22f;
            float centerY = rect.centerY();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(SOURCE_TEXT);
            canvas.drawLine(centerX - 5f, centerY - 2f, centerX, centerY + 3f, paint);
            canvas.drawLine(centerX, centerY + 3f, centerX + 5f, centerY - 2f, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    private void drawTrackText(Canvas canvas) {
        String title = fitText(nowPlaying.title, textRight - textLeft, titleSize);
        String subtitle = !nowPlaying.artist.isEmpty() ? nowPlaying.artist : nowPlaying.appLabel;
        if (subtitle.isEmpty()) {
            subtitle = nowPlaying.packageName;
        }
        subtitle = fitText(subtitle, textRight - textLeft, subtitleSize);
        text(canvas, title, textLeft, titleBaseline, titleSize, EnergyWidgetStyle.WHITE);
        text(canvas, subtitle, textLeft, subtitleBaseline, subtitleSize, EnergyWidgetStyle.MUTED);
    }

    private void drawPosterTrackText(Canvas canvas) {
        String title = fitText(nowPlaying.title, textRight - textLeft, titleSize);
        String subtitle = !nowPlaying.artist.isEmpty() ? nowPlaying.artist : nowPlaying.appLabel;
        if (subtitle.isEmpty()) {
            subtitle = nowPlaying.packageName;
        }
        subtitle = fitText(subtitle, textRight - textLeft, subtitleSize);
        centeredText(canvas, title, widthDp / 2f, titleBaseline, titleSize);
        centeredText(canvas, subtitle, widthDp / 2f, subtitleBaseline, subtitleSize);
    }

    private void centeredText(
            Canvas canvas, String value, float x, float baseline, float size) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(EnergyWidgetStyle.WHITE);
        paint.setTextSize(size * fontScale);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.SANS_SERIF);
        canvas.drawText(value, x, baseline, paint);
    }

    private void drawProgress(Canvas canvas) {
        long position = currentPosition();
        float fraction = MediaProgress.fraction(position, nowPlaying.duration);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(EnergyWidgetStyle.BORDER);
        canvas.drawRoundRect(
                textLeft, progressY - 2f, textRight, progressY + 2f, 2f, 2f, paint);
        if (fraction > 0f) {
            paint.setColor(ACCENT);
            canvas.drawRoundRect(
                    textLeft,
                    progressY - 2f,
                    textLeft + (textRight - textLeft) * fraction,
                    progressY + 2f,
                    2f,
                    2f,
                    paint);
        }
        if (tall && nowPlaying.duration > 0L) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(EnergyWidgetStyle.MUTED);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(14f * fontScale);
            paint.setTypeface(Typeface.SANS_SERIF);
            String timing =
                    MediaProgress.format(position)
                            + " / "
                            + MediaProgress.format(nowPlaying.duration);
            canvas.drawText(timing, textRight, progressY - 8f, paint);
        }
    }

    private void drawCompactProgress(Canvas canvas) {
        float fraction = MediaProgress.fraction(currentPosition(), nowPlaying.duration);
        if (fraction <= 0f) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ACCENT);
        canvas.drawRoundRect(8f, progressY - 2f, 8f + (widthDp - 16f) * fraction, progressY + 2f,
                2f, 2f, paint);
    }

    private void drawControls(Canvas canvas) {
        if (compact) {
            drawControlBackground(canvas, playRect, ZONE_PLAY);
            drawPlayPause(canvas, playRect);
            return;
        }
        drawControlBackground(canvas, previousRect, ZONE_PREVIOUS);
        drawControlBackground(canvas, playRect, ZONE_PLAY);
        drawControlBackground(canvas, nextRect, ZONE_NEXT);
        drawSkip(canvas, previousRect, false);
        drawPlayPause(canvas, playRect);
        drawSkip(canvas, nextRect, true);
    }

    private void drawControlBackground(Canvas canvas, RectF rect, int zone) {
        if (pressedZone != zone) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CONTROL_BACKGROUND);
        float radius = Math.min(rect.width(), rect.height()) / 2;
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint);
    }

    private void drawPlayPause(Canvas canvas, RectF rect) {
        float radius = Math.min(rect.width(), rect.height()) / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ACCENT);
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint);

        paint.setColor(0xff0d1622);
        if (nowPlaying.isPlaying()) {
            float barWidth = radius * .24f;
            float barHeight = radius * .9f;
            float gap = radius * .18f;
            float leftBar = rect.centerX() - gap / 2 - barWidth;
            float rightBar = rect.centerX() + gap / 2;
            canvas.drawRoundRect(
                    leftBar,
                    rect.centerY() - barHeight / 2,
                    leftBar + barWidth,
                    rect.centerY() + barHeight / 2,
                    barWidth / 2,
                    barWidth / 2,
                    paint);
            canvas.drawRoundRect(
                    rightBar,
                    rect.centerY() - barHeight / 2,
                    rightBar + barWidth,
                    rect.centerY() + barHeight / 2,
                    barWidth / 2,
                    barWidth / 2,
                    paint);
        } else {
            float size = radius * .52f;
            path.reset();
            path.moveTo(rect.centerX() - size * .55f, rect.centerY() - size);
            path.lineTo(rect.centerX() + size * .8f, rect.centerY());
            path.lineTo(rect.centerX() - size * .55f, rect.centerY() + size);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private void drawSkip(Canvas canvas, RectF rect, boolean forward) {
        float size = Math.min(rect.width(), rect.height()) * .3f;
        canvas.save();
        canvas.scale(forward ? 1f : -1f, 1f, rect.centerX(), rect.centerY());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(EnergyWidgetStyle.WHITE);
        path.reset();
        path.moveTo(rect.centerX() - size * 1.1f, rect.centerY() - size * .75f);
        path.lineTo(rect.centerX() - size * .05f, rect.centerY());
        path.lineTo(rect.centerX() - size * 1.1f, rect.centerY() + size * .75f);
        path.close();
        path.moveTo(rect.centerX() - size * .05f, rect.centerY() - size * .75f);
        path.lineTo(rect.centerX() + size, rect.centerY());
        path.lineTo(rect.centerX() - size * .05f, rect.centerY() + size * .75f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawRoundRect(
                rect.centerX() + size * 1.05f,
                rect.centerY() - size * .75f,
                rect.centerX() + size * 1.3f,
                rect.centerY() + size * .75f,
                size * .12f,
                size * .12f,
                paint);
        canvas.restore();
    }

    private void text(Canvas canvas, String value, float x, float baseline, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size * fontScale);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.SANS_SERIF);
        canvas.drawText(value, x, baseline, paint);
    }

    private String fitText(String value, float maxWidth, float size) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        paint.setTextSize(size * fontScale);
        paint.setTypeface(Typeface.SANS_SERIF);
        if (paint.measureText(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "…";
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (paint.measureText(value.substring(0, middle) + ellipsis) <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low == 0 ? "" : value.substring(0, low).trim() + ellipsis;
    }

    private long currentPosition() {
        return MediaProgress.position(
                nowPlaying.position,
                nowPlaying.updatedAt,
                nowPlaying.duration,
                nowPlaying.isPlaying(),
                System.currentTimeMillis());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() / density;
        float y = event.getY() / density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                pressedZone = zoneAt(x, y);
                removeCallbacks(longPress);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    removeCallbacks(longPress);
                    if (pressedZone != ZONE_NONE) {
                        pressedZone = ZONE_NONE;
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                int releaseZone = zoneAt(x, y);
                int downZone = pressedZone;
                pressedZone = ZONE_NONE;
                invalidate();
                if (downZone != ZONE_NONE && releaseZone == downZone) {
                    activate(releaseZone);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                pressedZone = ZONE_NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void activate(int zone) {
        if (zone == ZONE_SOURCES) {
            performClick();
            listener.onChooseSource();
            return;
        }
        if (nowPlaying.isEmpty()) {
            return;
        }
        performClick();
        if (zone == ZONE_OPEN) {
            listener.onOpenPlayer();
        } else if (zone == ZONE_PREVIOUS) {
            listener.onCommand(Command.PREVIOUS);
        } else if (zone == ZONE_PLAY) {
            listener.onCommand(Command.PLAY_PAUSE);
        } else if (zone == ZONE_NEXT) {
            listener.onCommand(Command.NEXT);
        }
    }

    private int zoneAt(float x, float y) {
        if (nowPlaying.isEmpty()) {
            return hit(emptySourceRect, x, y) ? ZONE_SOURCES : ZONE_NONE;
        }
        if (poster && hit(sourceRect, x, y)) {
            return ZONE_SOURCES;
        }
        if (hit(playRect, x, y)) {
            return ZONE_PLAY;
        }
        if (!compact && hit(previousRect, x, y)) {
            return ZONE_PREVIOUS;
        }
        if (!compact && hit(nextRect, x, y)) {
            return ZONE_NEXT;
        }
        return ZONE_OPEN;
    }

    private static boolean hit(RectF rect, float x, float y) {
        float margin = 6f;
        return x >= rect.left - margin
                && x <= rect.right + margin
                && y >= rect.top - margin
                && y <= rect.bottom + margin;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }
}
