package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.session.PlaybackState;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.restoremode.integration.media.NowPlayingClient;

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
    public void compactArtRoundsAllFourCorners() {
        Bitmap card = render(140, 124);

        assertBackground(card, 2, 2);
        assertBackground(card, 2, 121);
        assertBackground(card, 137, 2);
        assertArt(card, 40, 30);
    }

    private Bitmap render(int width, int height) {
        MediaWidgetView view = new MediaWidgetView(RuntimeEnvironment.getApplication(), listener);
        view.setBackgroundColor(BACKGROUND);
        view.setSources(
                Collections.singletonList(
                        new MediaSource("com.example.player", "Player", null)));
        view.update(playing(), solidArt());
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(card);
        canvas.drawColor(BACKGROUND);
        view.draw(canvas);
        return card;
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
