package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.system.Os;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, shadows = NowPlayingArtworkTest.FileOperations.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NowPlayingArtworkTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Implements(Os.class)
    public static class FileOperations {
        // Robolectric's API 30 filesystem does not implement rename(2).
        @Implementation
        protected static void rename(String from, String to) throws IOException {
            Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @Test
    public void publishedRevisionIdentifiesTheSamePngInTheQueryAndBroadcast() throws Exception {
        Robolectric.setupContentProvider(NowPlayingProvider.class, NowPlayingProvider.AUTHORITY);
        NowPlayingService service = Robolectric.buildService(NowPlayingService.class).get();
        MediaSession session = new MediaSession(context, "player");
        try {
            MediaController player = new MediaController(context, session.getSessionToken());
            ReflectionHelpers.setField(service, "current", player);

            long first = publish(service, player, Color.RED);
            assertTrue(first > 0L);
            assertEquals(first, publish(service, player, Color.RED));
            Uri firstImage = imageUri(first);
            try (InputStream alreadyOpen = context.getContentResolver().openInputStream(firstImage)) {
                long second = publish(service, player, Color.BLUE);
                assertNotEquals(first, second);
                assertEquals(Color.RED, BitmapFactory.decodeStream(alreadyOpen).getPixel(0, 0));
                assertThrows(FileNotFoundException.class,
                        () -> context.getContentResolver().openInputStream(firstImage));
                try (InputStream fresh = context.getContentResolver().openInputStream(imageUri(second))) {
                    assertEquals(Color.BLUE, BitmapFactory.decodeStream(fresh).getPixel(0, 0));
                }
                try (InputStream legacy = context.getContentResolver().openInputStream(NowPlayingProvider.ART_URI)) {
                    assertEquals(Color.BLUE, BitmapFactory.decodeStream(legacy).getPixel(0, 0));
                }
            }
            assertEquals(0L, publish(service, player, null));
        } finally {
            session.release();
        }
    }

    private static Uri imageUri(long revision) {
        return NowPlayingProvider.ART_URI.buildUpon()
                .appendQueryParameter("artRevision", Long.toString(revision)).build();
    }

    private long publish(NowPlayingService service, MediaController player, Integer color) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Same track");
        if (color != null) {
            Bitmap artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            artwork.eraseColor(color);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        shadowOf(player).setMetadata(metadata.build());
        ReflectionHelpers.callInstanceMethod(service, "publish", ClassParameter.from(String.class, "metadata"));
        Intent broadcast = ReflectionHelpers.callStaticMethod(NowPlayingService.class, "buildSnapshotIntent");

        try (Cursor cursor = context.getContentResolver().query(NowPlayingProvider.CONTENT_URI, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(color != null, broadcast.getBooleanExtra("hasArt", false));
            assertEquals(color != null, cursor.getInt(cursor.getColumnIndexOrThrow("hasArt")) == 1);
            long revision = cursor.getLong(cursor.getColumnIndexOrThrow("artRevision"));
            assertEquals(revision, broadcast.getLongExtra("artRevision", -1L));
            return revision;
        }
    }
}
