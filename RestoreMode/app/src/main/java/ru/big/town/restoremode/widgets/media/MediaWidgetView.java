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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Self-drawn current-player card: full-bleed album art, transport controls under it and an in-place
 * source picker behind the source icon in the bottom left corner.
 */
public final class MediaWidgetView extends View {
    enum Command {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT
    }

    interface Listener {
        void onCommand(Command command);

        void onOpenPlayer();

        void onSourceChosen(String packageName);

        void onSourceChooserOpened();
    }

    private enum Mode {
        COMPACT,
        STACKED,
        SIDE
    }

    private static final int ZONE_NONE = 0;
    private static final int ZONE_PREVIOUS = 1;
    private static final int ZONE_PLAY = 2;
    private static final int ZONE_NEXT = 3;
    private static final int ZONE_OPEN = 4;
    private static final int ZONE_SOURCE_BADGE = 5;
    private static final int ZONE_CHOOSER = 6;
    private static final int ZONE_SOURCE_OPTION_BASE = 100;

    private static final int ACCENT = 0xff79b5f1;
    private static final int CONTROL_BACKGROUND = 0x332f4c6a;
    private static final int PLACEHOLDER = 0xff263646;
    private static final int BADGE_BACKGROUND = 0xcc1b1f25;
    private static final int BADGE_BACKGROUND_ACTIVE = 0xcc2f4c6a;
    private static final int BADGE_BORDER = 0xff5a636e;
    private static final int CHOOSER_SCRIM = 0xe60d1014;
    private static final int OPTION_BACKGROUND = 0xff2b313a;
    private static final int OPTION_BACKGROUND_PRESSED = 0xff3a465a;
    private static final int OPTION_BORDER = 0xff626a74;
    private static final int MUTED_TEXT = EnergyWidgetStyle.MUTED;

    private static final float TEXT_PADDING = 12f;
    private static final float BADGE_MARGIN = 8f;
    private static final float PROGRESS_HEIGHT = 3f;
    private static final float COMPACT_WIDTH_DP = 160f;
    private static final float MIN_SIDE_TEXT_DP = 90f;
    private static final float DEFAULT_BORDER_DP = 1f;
    private static final float DEFAULT_CORNER_RADIUS_DP = 20f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF artRect = new RectF();
    private final RectF previousRect = new RectF();
    private final RectF playRect = new RectF();
    private final RectF nextRect = new RectF();
    private final RectF sourceBadgeRect = new RectF();
    private final RectF chooserRect = new RectF();
    private final RectF scratchRect = new RectF();
    private final float[] artRadii = new float[8];
    private final List<MediaSourceGrid.Cell> sourceCells = new ArrayList<>();
    private final Drawable placeholder;
    private final Listener listener;
    private final float density;
    private final float fontScale;
    private final float touchSlop;

    private NowPlayingClient.NowPlaying nowPlaying = new NowPlayingClient.NowPlaying();
    private List<MediaSource> sources = Collections.emptyList();
    private boolean sourcesLoaded;
    private Bitmap art;
    private Bitmap sourceIcon;
    private boolean chooserOpen;
    private boolean sourceCellsDirty = true;

    private float widthDp;
    private float heightDp;
    private float borderDp = DEFAULT_BORDER_DP;
    private float cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP;
    private float textLeft;
    private float textRight;
    private float titleBaseline;
    private float subtitleBaseline;
    private float titleSize;
    private float subtitleSize;
    private float progressY;
    private float progressLeft;
    private float progressRight;
    private boolean showTiming;
    private Mode mode = Mode.STACKED;
    private int pressedZone = ZONE_NONE;
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

    /** Border and corner radius of the dashboard tile so the art follows its rounded frame. */
    public void setTileFrame(int tileBorderDp, int tileCornerRadiusDp) {
        borderDp = Math.max(0, tileBorderDp);
        cornerRadiusDp = Math.max(0, tileCornerRadiusDp);
        sourceCellsDirty = true;
        layoutContent();
        invalidate();
    }

    void update(NowPlayingClient.NowPlaying snapshot, Bitmap albumArt) {
        NowPlayingClient.NowPlaying next =
                snapshot == null ? new NowPlayingClient.NowPlaying() : snapshot;
        if (!nowPlaying.packageName.equals(next.packageName)) {
            chooserOpen = false;
            sourceCellsDirty = true;
        }
        nowPlaying = next;
        art = albumArt;
        updateContentDescription();
        invalidate();
    }

    /** {@code null} keeps the "still loading" state; an empty list means no media applications. */
    void setSources(List<MediaSource> loaded) {
        sources = loaded == null ? Collections.emptyList() : loaded;
        sourcesLoaded = loaded != null;
        sourceCellsDirty = true;
        invalidate();
    }

    void setSourceIcon(Bitmap icon) {
        sourceIcon = icon;
        invalidate();
    }

    private void updateContentDescription() {
        if (nowPlaying.isEmpty()) {
            setContentDescription("Управление музыкой: источник не выбран. Список источников.");
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
                        + ". Нажмите на обложку, чтобы открыть плеер. Кнопки управления музыкой"
                        + " и выбора источника.");
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
        sourceBadgeRect.setEmpty();
        chooserRect.setEmpty();
        sourceCellsDirty = true;
        if (widthDp <= 0 || heightDp <= 0) {
            return;
        }
        chooserRect.set(0f, 0f, widthDp, heightDp);
        if (widthDp < COMPACT_WIDTH_DP) {
            mode = Mode.COMPACT;
        } else if (stackedFits()) {
            mode = Mode.STACKED;
        } else {
            mode = sideFits() ? Mode.SIDE : Mode.COMPACT;
        }
        if (mode == Mode.COMPACT) {
            layoutCompact();
        } else if (mode == Mode.STACKED) {
            layoutStacked();
        } else {
            layoutSide();
        }
    }

    private boolean stackedFits() {
        return heightDp - stackedBlockHeight() >= widthDp * .45f;
    }

    private float stackedBlockHeight() {
        float title = titleSizeFor(widthDp);
        float subtitle = subtitleSizeFor(title);
        float radius = controlRadiusFor(widthDp);
        return 40f + title * .8f + subtitle * 1.3f + radius * 2f;
    }

    private boolean sideFits() {
        float artSize = Math.min(heightDp - borderDp * 2f, widthDp * .55f);
        return widthDp - borderDp - artSize - 14f - TEXT_PADDING >= MIN_SIDE_TEXT_DP;
    }

    private static float titleSizeFor(float width) {
        return clamp(width * .055f, 18f, 26f);
    }

    private static float subtitleSizeFor(float title) {
        return clamp(title * .72f, 13f, 19f);
    }

    private static float controlRadiusFor(float width) {
        return clamp(width * .06f, 16f, 28f);
    }

    private void layoutCompact() {
        artRect.set(borderDp, borderDp, widthDp - borderDp, heightDp - borderDp);
        setArtRadii(true);
        float radius = Math.min(widthDp, heightDp) * .18f;
        playRect.set(
                widthDp / 2f - radius,
                heightDp / 2f - radius,
                widthDp / 2f + radius,
                heightDp / 2f + radius);
        progressY = heightDp - 10f;
        progressLeft = 10f;
        progressRight = widthDp - 10f;
        showTiming = false;
    }

    private void layoutStacked() {
        float radius = controlRadiusFor(widthDp);
        float artBottom = Math.max(borderDp + 40f, heightDp - stackedBlockHeight());
        artRect.set(borderDp, borderDp, widthDp - borderDp, artBottom);
        setArtRadii(false);

        titleSize = titleSizeFor(widthDp);
        subtitleSize = subtitleSizeFor(titleSize);
        textLeft = TEXT_PADDING;
        textRight = widthDp - TEXT_PADDING;

        float controlsCenterY = heightDp - 10f - radius;
        progressY = controlsCenterY - radius - 12f;
        progressLeft = textLeft;
        progressRight = textRight;
        subtitleBaseline = progressY - 10f;
        titleBaseline = subtitleBaseline - subtitleSize * 1.3f;
        showTiming = heightDp >= 200f;

        layoutSourceBadge();
        layoutControls(controlsCenterY, radius);
        nudgeControlsPastBadge();
    }

    private void layoutSide() {
        float artSize = Math.min(heightDp - borderDp * 2f, widthDp * .55f);
        artRect.set(borderDp, borderDp, borderDp + artSize, heightDp - borderDp);
        setArtRadii(false);

        textLeft = artRect.right + 14f;
        textRight = widthDp - TEXT_PADDING;
        float availableWidth = Math.max(1f, textRight - textLeft);
        float radius = clamp(Math.min(availableWidth / 8f, heightDp * .18f), 12f, 24f);
        titleSize = clamp(Math.min(availableWidth * .12f, heightDp * .22f), 14f, 26f);
        subtitleSize = clamp(titleSize * .75f, 12f, 18f);

        float blockHeight =
                titleSize * .8f + subtitleSize * 1.3f + 19f + radius * 2f;
        float blockTop = Math.max(6f, (heightDp - blockHeight) / 2f);
        titleBaseline = blockTop + titleSize * .8f;
        subtitleBaseline = titleBaseline + subtitleSize * 1.3f;
        progressY = subtitleBaseline + 7f;
        progressLeft = textLeft;
        progressRight = textRight;
        float controlsCenterY = progressY + 9f + radius;
        showTiming = false;

        layoutControls(controlsCenterY, radius);
        layoutSourceBadge();
    }

    private void layoutControls(float centerY, float radius) {
        float gap = Math.max(6f, radius * .4f);
        float total = radius * 6f + gap * 2f;
        float start = textLeft + Math.max(0f, (textRight - textLeft - total) / 2f);
        previousRect.set(start, centerY - radius, start + radius * 2f, centerY + radius);
        playRect.set(
                previousRect.right + gap,
                centerY - radius,
                previousRect.right + gap + radius * 2f,
                centerY + radius);
        nextRect.set(
                playRect.right + gap,
                centerY - radius,
                playRect.right + gap + radius * 2f,
                centerY + radius);
    }

    /** Narrow tiles keep the transport row clear of the source icon instead of overlapping it. */
    private void nudgeControlsPastBadge() {
        if (previousRect.isEmpty() || sourceBadgeRect.isEmpty()) {
            return;
        }
        float overlap = sourceBadgeRect.right + 4f - previousRect.left;
        float room = textRight - nextRect.right;
        float shift = Math.min(overlap, room);
        if (shift <= 0f) {
            return;
        }
        previousRect.offset(shift, 0f);
        playRect.offset(shift, 0f);
        nextRect.offset(shift, 0f);
    }

    private void layoutSourceBadge() {
        float diameter = clamp(widthDp * .085f, 24f, 34f);
        float left = BADGE_MARGIN;
        float top = heightDp - BADGE_MARGIN - diameter;
        sourceBadgeRect.set(left, top, left + diameter, top + diameter);
    }

    /** The art follows the tile frame: rounded where it touches the tile corner. */
    private void setArtRadii(boolean allCorners) {
        float top = Math.max(0f, cornerRadiusDp - borderDp);
        float bottom = allCorners ? top : 0f;
        artRadii[0] = top;
        artRadii[1] = top;
        artRadii[2] = top;
        artRadii[3] = top;
        artRadii[4] = bottom;
        artRadii[5] = bottom;
        artRadii[6] = bottom;
        artRadii[7] = bottom;
    }

    private boolean chooserVisible() {
        return mode != Mode.COMPACT && (chooserOpen || nowPlaying.isEmpty());
    }

    private List<MediaSourceGrid.Cell> sourceCells() {
        if (!sourceCellsDirty) {
            return sourceCells;
        }
        sourceCellsDirty = false;
        sourceCells.clear();
        if (!chooserVisible() || widthDp <= 0f) {
            return sourceCells;
        }
        float padding = 14f;
        sourceCells.addAll(
                MediaSourceGrid.arrange(
                        sources.size(),
                        padding,
                        padding,
                        Math.max(0f, widthDp - padding * 2f),
                        Math.max(0f, heightDp - padding * 2f)));
        return sourceCells;
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
            if (mode == Mode.COMPACT) {
                drawArt(canvas);
            } else {
                drawSourceChooser(canvas, false);
            }
        } else {
            drawArt(canvas);
            if (mode == Mode.COMPACT) {
                drawCompactProgress(canvas);
                drawControls(canvas);
            } else {
                drawTrackText(canvas);
                drawProgress(canvas);
                drawControls(canvas);
                if (chooserOpen) {
                    drawSourceChooser(canvas, true);
                }
                drawSourceBadge(canvas);
            }
        }
        canvas.restore();
    }

    private void drawArt(Canvas canvas) {
        path.reset();
        path.addRoundRect(artRect, artRadii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        if (art == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PLACEHOLDER);
            canvas.drawRect(artRect, paint);
            drawPlaceholder(canvas, artRect, .27f);
        } else {
            drawCropped(canvas, art, artRect);
        }
        canvas.restore();
    }

    private void drawPlaceholder(Canvas canvas, RectF target, float insetShare) {
        if (placeholder == null) {
            return;
        }
        float inset = Math.min(target.width(), target.height()) * insetShare;
        placeholder.setBounds(
                Math.round(target.left + inset),
                Math.round(target.top + inset),
                Math.round(target.right - inset),
                Math.round(target.bottom - inset));
        placeholder.setAlpha(200);
        placeholder.draw(canvas);
    }

    private void drawCropped(Canvas canvas, Bitmap bitmap, RectF target) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return;
        }
        Rect source = new Rect();
        float scale =
                Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
        float cropWidth = target.width() / scale;
        float cropHeight = target.height() / scale;
        source.set(
                Math.round((bitmap.getWidth() - cropWidth) / 2f),
                Math.round((bitmap.getHeight() - cropHeight) / 2f),
                Math.round((bitmap.getWidth() + cropWidth) / 2f),
                Math.round((bitmap.getHeight() + cropHeight) / 2f));
        paint.setFilterBitmap(true);
        canvas.drawBitmap(bitmap, source, target, paint);
        paint.setFilterBitmap(false);
    }

    private void drawTrackText(Canvas canvas) {
        String subtitle = !nowPlaying.artist.isEmpty() ? nowPlaying.artist : nowPlaying.appLabel;
        if (subtitle.isEmpty()) {
            subtitle = nowPlaying.packageName;
        }
        String title = fitText(nowPlaying.title, textRight - textLeft, titleSize);
        subtitle = fitText(subtitle, textRight - textLeft, subtitleSize);
        text(canvas, title, textLeft, titleBaseline, titleSize, EnergyWidgetStyle.WHITE);
        text(canvas, subtitle, textLeft, subtitleBaseline, subtitleSize, MUTED_TEXT);
    }

    private void drawProgress(Canvas canvas) {
        long position = currentPosition();
        float fraction = MediaProgress.fraction(position, nowPlaying.duration);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(EnergyWidgetStyle.BORDER);
        canvas.drawRoundRect(
                progressLeft,
                progressY - PROGRESS_HEIGHT / 2f,
                progressRight,
                progressY + PROGRESS_HEIGHT / 2f,
                PROGRESS_HEIGHT / 2f,
                PROGRESS_HEIGHT / 2f,
                paint);
        if (fraction > 0f) {
            paint.setColor(ACCENT);
            canvas.drawRoundRect(
                    progressLeft,
                    progressY - PROGRESS_HEIGHT / 2f,
                    progressLeft + (progressRight - progressLeft) * fraction,
                    progressY + PROGRESS_HEIGHT / 2f,
                    PROGRESS_HEIGHT / 2f,
                    PROGRESS_HEIGHT / 2f,
                    paint);
        }
        if (showTiming && nowPlaying.duration > 0L) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(MUTED_TEXT);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(13f * fontScale);
            paint.setTypeface(Typeface.SANS_SERIF);
            String timing =
                    MediaProgress.format(position)
                            + " / "
                            + MediaProgress.format(nowPlaying.duration);
            canvas.drawText(timing, progressRight, progressY - 8f, paint);
        }
    }

    private void drawCompactProgress(Canvas canvas) {
        float fraction = MediaProgress.fraction(currentPosition(), nowPlaying.duration);
        if (fraction <= 0f) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ACCENT);
        canvas.drawRoundRect(
                progressLeft,
                progressY - 2f,
                progressLeft + (progressRight - progressLeft) * fraction,
                progressY + 2f,
                2f,
                2f,
                paint);
    }

    private void drawControls(Canvas canvas) {
        drawControlBackground(canvas, playRect, ZONE_PLAY);
        drawPlayPause(canvas, playRect);
        if (mode == Mode.COMPACT) {
            return;
        }
        drawControlBackground(canvas, previousRect, ZONE_PREVIOUS);
        drawControlBackground(canvas, nextRect, ZONE_NEXT);
        drawSkip(canvas, previousRect, false);
        drawSkip(canvas, nextRect, true);
    }

    private void drawControlBackground(Canvas canvas, RectF rect, int zone) {
        if (pressedZone != zone) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CONTROL_BACKGROUND);
        float radius = Math.min(rect.width(), rect.height()) / 2f;
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint);
    }

    private void drawPlayPause(Canvas canvas, RectF rect) {
        float radius = Math.min(rect.width(), rect.height()) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ACCENT);
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint);

        paint.setColor(0xff0d1622);
        if (nowPlaying.isPlaying()) {
            float barWidth = radius * .24f;
            float barHeight = radius * .9f;
            float gap = radius * .18f;
            float leftBar = rect.centerX() - gap / 2f - barWidth;
            float rightBar = rect.centerX() + gap / 2f;
            canvas.drawRoundRect(
                    leftBar,
                    rect.centerY() - barHeight / 2f,
                    leftBar + barWidth,
                    rect.centerY() + barHeight / 2f,
                    barWidth / 2f,
                    barWidth / 2f,
                    paint);
            canvas.drawRoundRect(
                    rightBar,
                    rect.centerY() - barHeight / 2f,
                    rightBar + barWidth,
                    rect.centerY() + barHeight / 2f,
                    barWidth / 2f,
                    barWidth / 2f,
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

    private void drawSourceBadge(Canvas canvas) {
        if (sourceBadgeRect.isEmpty()) {
            return;
        }
        float radius = sourceBadgeRect.width() / 2f;
        float centerX = sourceBadgeRect.centerX();
        float centerY = sourceBadgeRect.centerY();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chooserOpen ? BADGE_BACKGROUND_ACTIVE : BADGE_BACKGROUND);
        canvas.drawCircle(centerX, centerY, radius, paint);

        float inner = radius * .82f;
        path.reset();
        path.addCircle(centerX, centerY, inner, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        if (sourceIcon != null) {
            float side = inner * 1.4f;
            scratchRect.set(
                    centerX - side / 2f,
                    centerY - side / 2f,
                    centerX + side / 2f,
                    centerY + side / 2f);
            drawCropped(canvas, sourceIcon, scratchRect);
        } else {
            drawPlaceholder(canvas, sourceBadgeRect, .3f);
        }
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(chooserOpen ? 2f : 1f);
        paint.setColor(chooserOpen ? ACCENT : BADGE_BORDER);
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStrokeWidth(0f);
    }

    private void drawSourceChooser(Canvas canvas, boolean scrim) {
        if (scrim) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(CHOOSER_SCRIM);
            canvas.drawRect(chooserRect, paint);
        }
        List<MediaSourceGrid.Cell> cells = sourceCells();
        if (cells.isEmpty()) {
            if (sourcesLoaded) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(MUTED_TEXT);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(15f * fontScale);
                paint.setTypeface(Typeface.SANS_SERIF);
                canvas.drawText("Нет источников", widthDp / 2f, heightDp / 2f, paint);
            }
            return;
        }
        for (int index = 0; index < cells.size(); index++) {
            drawSourceOption(canvas, cells.get(index), index);
        }
    }

    private void drawSourceOption(Canvas canvas, MediaSourceGrid.Cell cell, int index) {
        float radius = (cell.right - cell.left) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(
                pressedZone == ZONE_SOURCE_OPTION_BASE + index
                        ? OPTION_BACKGROUND_PRESSED
                        : OPTION_BACKGROUND);
        canvas.drawCircle(cell.centerX(), cell.centerY(), radius, paint);

        Bitmap icon = index < sources.size() ? sources.get(index).icon : null;
        float inset = radius * .14f;
        scratchRect.set(
                cell.left + inset,
                cell.top + inset,
                cell.right - inset,
                cell.bottom - inset);
        if (icon != null) {
            drawCropped(canvas, icon, scratchRect);
        } else {
            scratchRect.set(
                    cell.left + radius * .5f,
                    cell.top + radius * .5f,
                    cell.right - radius * .5f,
                    cell.bottom - radius * .5f);
            drawPlaceholder(canvas, scratchRect, 0f);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(OPTION_BORDER);
        canvas.drawCircle(cell.centerX(), cell.centerY(), radius, paint);
        paint.setStrokeWidth(0f);
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
        if (zone == ZONE_CHOOSER) {
            performClick();
            closeChooser();
            return;
        }
        if (zone >= ZONE_SOURCE_OPTION_BASE) {
            int index = zone - ZONE_SOURCE_OPTION_BASE;
            if (index >= sources.size()) {
                return;
            }
            performClick();
            closeChooser();
            listener.onSourceChosen(sources.get(index).packageName);
            return;
        }
        if (zone == ZONE_SOURCE_BADGE) {
            performClick();
            chooserOpen = !chooserOpen;
            sourceCellsDirty = true;
            invalidate();
            if (chooserOpen) {
                listener.onSourceChooserOpened();
            }
            return;
        }
        if (nowPlaying.isEmpty() || zone == ZONE_NONE) {
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

    private void closeChooser() {
        chooserOpen = false;
        sourceCellsDirty = true;
        invalidate();
    }

    private int zoneAt(float x, float y) {
        if (chooserVisible()) {
            if (chooserOpen && hit(sourceBadgeRect, x, y)) {
                return ZONE_SOURCE_BADGE;
            }
            List<MediaSourceGrid.Cell> cells = sourceCells();
            for (int index = 0; index < cells.size(); index++) {
                if (cells.get(index).contains(x, y, 4f)) {
                    return ZONE_SOURCE_OPTION_BASE + index;
                }
            }
            return ZONE_CHOOSER;
        }
        if (mode == Mode.COMPACT) {
            return hit(playRect, x, y) ? ZONE_PLAY : ZONE_OPEN;
        }
        if (hit(sourceBadgeRect, x, y)) {
            return ZONE_SOURCE_BADGE;
        }
        if (hit(playRect, x, y)) {
            return ZONE_PLAY;
        }
        if (hit(previousRect, x, y)) {
            return ZONE_PREVIOUS;
        }
        if (hit(nextRect, x, y)) {
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

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }
}
