package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import ru.big.town.restoremode.integration.media.NowPlayingClient;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "mdpi", application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MediaWidgetArtworkTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private ArtworkProvider provider;
    private MediaWidgetDashboard dashboard;
    private View view;

    @Before
    public void createWidget() throws Exception {
        provider = Robolectric.setupContentProvider(
                ArtworkProvider.class, NowPlayingClient.CONTENT_URI.getAuthority());
        provider.writeCover(Color.RED);
        dashboard = new MediaWidgetDashboard(context, ignored -> {});
        view = dashboard.create();
        view.measure(View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 440, 640);
    }

    @After
    public void closeWidget() {
        dashboard.close();
    }

    @Test
    public void lateArtworkReplacesThePreviousCoverWithTheSameTrackMetadata() throws Exception {
        dashboard.setActive(true);
        finishArtworkDelivery();
        assertTrue(isRed(renderCover()));

        tapNext();
        finishArtworkDelivery();
        assertTrue(view.getContentDescription().toString().contains("Next"));
        provider.writeCover(Color.BLUE);
        publish("Next", 2L, 0);
        assertTrue("The next track should show its late blue cover", isBlue(renderCover()));
        assertEquals("2", provider.lastOpened.getQueryParameter("artRevision"));

        publish("Next", 2L, 1_000);
        assertEquals("Only the two distinct images should be read", 2, provider.reads.get());
    }

    @Test
    public void oldNativeWithoutArtworkRevisionsReloadsOnTrackChange() throws Exception {
        provider.legacy = true;
        dashboard.setActive(true);
        finishArtworkDelivery();
        assertTrue(isRed(renderCover()));

        provider.writeCover(Color.BLUE);
        publish("Next", null, 0);
        publish("Next", null, 1_000);
        assertTrue(isBlue(renderCover()));
        assertEquals(2, provider.reads.get());
        assertEquals(NowPlayingClient.ART_URI, provider.lastOpened);
    }

    @Test
    public void clearingArtworkDiscardsAPendingImageAndAllowsTheSameRevisionToReturn() throws Exception {
        dashboard.setActive(true);
        finishArtworkDelivery();
        provider.writeCover(Color.BLUE);
        context.sendBroadcast(playbackUpdate("Next", 2L, 0));
        context.sendBroadcast(playbackUpdate("Next", 0L, 0).putExtra("hasArt", false));
        finishArtworkDelivery();

        int placeholder = renderCover();
        assertFalse(isRed(placeholder));
        assertFalse(isBlue(placeholder));
        publish("Next", 2L, 0);
        assertTrue(isBlue(renderCover()));
    }

    private void tapNext() {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 286, 604, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, 286, 604, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void publish(String title, Long revision, long position) throws Exception {
        context.sendBroadcast(playbackUpdate(title, revision, position));
        finishArtworkDelivery();
    }

    private static Intent playbackUpdate(String title, Long revision, long position) {
        Intent update = new Intent(NowPlayingClient.ACTION_NOW_PLAYING)
                .putExtra("package", "example.player")
                .putExtra("title", title)
                .putExtra("hasArt", true)
                .putExtra("state", PlaybackState.STATE_PLAYING)
                .putExtra("position", position)
                .putExtra("updatedAt", 10_000L + position);
        if (revision != null) {
            update.putExtra("artRevision", revision.longValue());
        }
        return update;
    }

    private void finishArtworkDelivery() throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        ExecutorService worker = ReflectionHelpers.getField(dashboard, "artExecutor");
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private int renderCover() {
        Bitmap frame = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));
        int color = frame.getPixel(180, 160);
        frame.recycle();
        return color;
    }

    private static boolean isRed(int color) {
        return Color.red(color) - Color.blue(color) > 128;
    }

    private static boolean isBlue(int color) {
        return Color.blue(color) - Color.red(color) > 128;
    }

    public static class ArtworkProvider extends ContentProvider {
        final AtomicInteger reads = new AtomicInteger();
        volatile Uri lastOpened;
        boolean legacy;
        private File image;

        @Override
        public boolean onCreate() {
            image = new File(getContext().getCacheDir(), "cover.png");
            return true;
        }

        void writeCover(int color) throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            try (FileOutputStream stream = new FileOutputStream(image)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally {
                bitmap.recycle();
            }
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = legacy ? new String[]{"package", "title", "hasArt"}
                    : new String[]{"package", "title", "hasArt", "artRevision"};
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow().add("example.player").add("First").add(1);
            if (!legacy) row.add(1L);
            return cursor;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            reads.incrementAndGet();
            lastOpened = uri;
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            assertEquals("media_control", method);
            assertEquals("next", arg);
            getContext().sendBroadcast(playbackUpdate("Next", 1L, 0));
            Bundle result = new Bundle();
            result.putBoolean("handled", true);
            return result;
        }

        @Override
        public String getType(Uri uri) {
            return "image/png";
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
