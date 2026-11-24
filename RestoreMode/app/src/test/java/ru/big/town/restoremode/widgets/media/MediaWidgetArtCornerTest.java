package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.session.PlaybackState;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.restoremode.integration.media.NowPlayingClient;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetStyle;

import java.util.Collections;

/** Album art must follow the rounded tile frame in every card layout. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "mdpi")
public class MediaWidgetArtCornerTest {
    private static final int BACKGROUND = Color.BLACK;

    private final MediaWidgetView.Listener listener =
            new MediaWidgetView.Listener() {
                @Override
                public void onCommand(MediaWidgetView.Command command) {}

                @Override
                public void onOpenPlayer() {}

                @Override
                public void onSourceChosen(String packageName) {}

                @Override
                public void onSourceChooserOpened() {}
            };

    @Test
    public void stackedArtRoundsTopCornersAndKeepsItsBottomEdgeStraight() {
        Bitmap card = render(440, 640);

        assertBackground(card, 2, 2);
        assertArt(card, 24, 24);
        assertArt(card, 2, 500);
    }

    @Test
    public void sideArtRoundsBothLeftCornersOfTheTile() {
        Bitmap card = render(580, 124);

        assertBackground(card, 2, 2);
        assertBackground(card, 2, 121);
        assertArt(card, 24, 24);
        assertArt(card, 60, 100);
    }

    @Test
    public void threeByTwoArtFillsTheHeightInsideTheTileFrame() {
        Bitmap card = render(439, 270);

        assertArt(card, 120, 1);
        assertArt(card, 120, 268);
        assertArt(card, 1, 135);
        assertBackground(card, 120, 0);
        assertBackground(card, 120, 269);
        assertBackground(card, 2, 2);
        assertBackground(card, 2, 267);
    }

    @Test
    public void redrawingAnOpaqueCoverKeepsItsOriginalColorOnATransparentTile() {
        MediaWidgetView view = createView(439, 270, Color.TRANSPARENT, solidArt());
        draw(view).recycle();

        Bitmap secondFrame = draw(view);
        assertArt(secondFrame, 120, 80);
        assertEquals(Color.TRANSPARENT, secondFrame.getPixel(400, 26));
    }

    @Test
    public void transparentCoverPixelsHaveAnOpaqueBackingOnlyInsideTheArt() {
        Bitmap artwork = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Paint red = new Paint();
        red.setColor(Color.RED);
        new Canvas(artwork).drawRect(0, 0, 128, 256, red);
        Bitmap card = draw(createView(439, 270, Color.TRANSPARENT, artwork));

        assertArt(card, 60, 90);
        assertEquals(255, Color.alpha(card.getPixel(180, 90)));
        assertEquals(Color.TRANSPARENT, card.getPixel(400, 26));
    }

    @Test
    public void allTextLayoutsLeaveSpaceBelowTheArtistBeforeTheProgressBar() {
        int[] widths = {141, 290, 439, 588};
        int[] heights = {131, 270, 409, 547, 685};
        Bitmap artwork = solidArt();
        for (int columns = 2; columns <= widths.length; columns++) {
            for (int rows = 1; rows <= MediaWidgetLayout.maxHeight(columns); rows++) {
                MediaWidgetView view = createView(widths[columns - 1], heights[rows - 1], BACKGROUND, artwork);
                TrackSpacingCanvas canvas = new TrackSpacingCanvas();
                view.draw(canvas);
                float gap = canvas.progressTop - canvas.artistBottom;
                assertTrue(columns + "×" + rows + " has an artist-to-progress gap of " + gap,
                        gap >= 9.99f);
            }
        }
    }

    @Test
    public void compactArtRoundsAllFourCorners() {
        Bitmap card = render(140, 124);

        assertBackground(card, 2, 2);
        assertBackground(card, 2, 121);
        assertBackground(card, 137, 2);
        assertArt(card, 40, 30);
    }

    private Bitmap render(int width, int height) {
        return draw(createView(width, height, BACKGROUND, solidArt()));
    }

    private MediaWidgetView createView(int width, int height, int background, Bitmap artwork) {
        MediaWidgetView view = new MediaWidgetView(RuntimeEnvironment.getApplication(), listener);
        view.setBackgroundColor(background);
        view.setSources(
                Collections.singletonList(
                        new MediaSource("com.example.player", "Player", null)));
        view.update(playing(), artwork);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private static Bitmap draw(View view) {
        Bitmap card = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(card));
        return card;
    }

    private static final class TrackSpacingCanvas extends Canvas {
        float artistBottom = Float.NaN;
        float progressTop = Float.NaN;

        @Override
        public void drawText(String value, float x, float baseline, Paint paint) {
            if ("Soundgarden".equals(value)) {
                artistBottom = baseline + paint.descent();
            }
            super.drawText(value, x, baseline, paint);
        }

        @Override
        public void drawRoundRect(float left, float top, float right, float bottom,
                float radiusX, float radiusY, Paint paint) {
            if (paint.getColor() == EnergyWidgetStyle.BORDER) {
                progressTop = top;
            }
            super.drawRoundRect(left, top, right, bottom, radiusX, radiusY, paint);
        }
    }

    private static void assertBackground(Bitmap card, int x, int y) {
        assertEquals(
                "expected tile background at " + x + "x" + y,
                BACKGROUND,
                card.getPixel(x, y));
    }

    private static void assertArt(Bitmap card, int x, int y) {
        assertEquals(
                "expected album art at " + x + "x" + y,
                Color.RED,
                card.getPixel(x, y));
    }

    private static Bitmap solidArt() {
        Bitmap art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        new Canvas(art).drawColor(Color.RED);
        return art;
    }

    private static NowPlayingClient.NowPlaying playing() {
        NowPlayingClient.NowPlaying nowPlaying = new NowPlayingClient.NowPlaying();
        nowPlaying.title = "Black Hole Sun";
        nowPlaying.artist = "Soundgarden";
        nowPlaying.packageName = "com.example.player";
        nowPlaying.state = PlaybackState.STATE_PLAYING;
        nowPlaying.duration = 120_000L;
        return nowPlaying;
    }
}
