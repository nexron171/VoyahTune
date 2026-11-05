package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import ru.big.town.restoremode.integration.media.NowPlayingClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class MediaWidgetViewTest {
    private final List<String> chosenSources = new ArrayList<>();
    private final List<String> openedPlayers = new ArrayList<>();
    private final List<MediaWidgetView.Command> commands = new ArrayList<>();
    private int chooserOpenings;

    private final MediaWidgetView.Listener listener =
            new MediaWidgetView.Listener() {
                @Override
                public void onCommand(MediaWidgetView.Command command) {
                    commands.add(command);
                }

                @Override
                public void onOpenPlayer() {
                    openedPlayers.add("player");
                }

                @Override
                public void onSourceChosen(String packageName) {
                    chosenSources.add(packageName);
                }

                @Override
                public void onSourceChooserOpened() {
                    chooserOpenings++;
                }
            };

    @Test
    public void tappingArtOpensPlayerAndTransportButtonsSendCommands() {
        MediaWidgetView view = createView(440f, 640f);
        view.setSources(source("com.example.player"));
        view.update(playing(), null);

        tapDp(view, 220f, 120f);
        assertEquals(1, openedPlayers.size());
        assertTrue(commands.isEmpty());

        tapDp(view, 220f, 604f);
        assertEquals(Arrays.asList(MediaWidgetView.Command.PLAY_PAUSE), commands);

        tapDp(view, 154f, 604f);
        tapDp(view, 286f, 604f);
        assertEquals(
                Arrays.asList(
                        MediaWidgetView.Command.PLAY_PAUSE,
                        MediaWidgetView.Command.PREVIOUS,
                        MediaWidgetView.Command.NEXT),
                commands);
        assertEquals(1, openedPlayers.size());
        assertTrue(chosenSources.isEmpty());
    }

    @Test
    public void sourceIconOpensChooserAndPickingStartsThePlayer() {
        MediaWidgetView view = createView(440f, 640f);
        view.setSources(source("com.example.player"));
        view.update(playing(), null);

        tapDp(view, 25f, 615f);
        assertEquals(1, chooserOpenings);
        assertTrue(chosenSources.isEmpty());

        tapDp(view, 220f, 320f);
        assertEquals(Collections.singletonList("com.example.player"), chosenSources);
        assertTrue(openedPlayers.isEmpty());
    }

    @Test
    public void tapOutsideChooserIconsClosesChooserWithoutOpeningPlayer() {
        MediaWidgetView view = createView(440f, 640f);
        view.setSources(source("com.example.player"));
        view.update(playing(), null);

        tapDp(view, 25f, 615f);
        tapDp(view, 40f, 60f);
        assertTrue(chosenSources.isEmpty());
        assertTrue(openedPlayers.isEmpty());

        tapDp(view, 220f, 120f);
        assertEquals(1, openedPlayers.size());
    }

    @Test
    public void emptySnapshotOffersSourceIconsAndStartsSelection() {
        MediaWidgetView view = createView(440f, 640f);
        view.setSources(source("com.example.player"));
        view.update(new NowPlayingClient.NowPlaying(), null);

        tapDp(view, 40f, 60f);
        assertTrue(openedPlayers.isEmpty());

        tapDp(view, 220f, 320f);
        assertEquals(Collections.singletonList("com.example.player"), chosenSources);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void compactTileKeepsArtAndPlayButtonWithoutSourcePicker() {
        MediaWidgetView view = createView(140f, 124f);
        view.setSources(source("com.example.player"));
        view.update(playing(), null);

        tapDp(view, 70f, 20f);
        tapDp(view, 20f, 104f);
        assertEquals(2, openedPlayers.size());
        assertEquals(0, chooserOpenings);

        tapDp(view, 70f, 62f);
        assertEquals(Arrays.asList(MediaWidgetView.Command.PLAY_PAUSE), commands);
    }

    @Test
    public void wideTileKeepsSourceIconInTheBottomLeftCorner() {
        MediaWidgetView view = createView(580f, 124f);
        view.setSources(source("com.example.player"));
        view.update(playing(), null);

        tapDp(view, 40f, 40f);
        assertEquals(1, openedPlayers.size());

        tapDp(view, 25f, 99f);
        assertEquals(1, chooserOpenings);
    }

    private static List<MediaSource> source(String packageName) {
        return Collections.singletonList(new MediaSource(packageName, "Player", null));
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

    private MediaWidgetView createView(float widthDp, float heightDp) {
        MediaWidgetView view =
                new MediaWidgetView(RuntimeEnvironment.getApplication(), listener);
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        return view;
    }

    private static void tapDp(View view, float xDp, float yDp) {
        float density = view.getResources().getDisplayMetrics().density;
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, xDp * density, yDp * density, 0));
        view.dispatchTouchEvent(
                MotionEvent.obtain(
                        now, now + 20L, MotionEvent.ACTION_UP, xDp * density, yDp * density, 0));
    }
}
