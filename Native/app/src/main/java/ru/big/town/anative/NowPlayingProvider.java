package ru.big.town.anative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Provider «сейчас играет» — точка чтения для наших поверхностей (VoyahTune и др.).
 *
 * <ul>
 *   <li>{@code query(content://ru.big.town.anative.nowplaying)} → одна строка со снимком
 *       (см. колонки в {@link #COLUMNS}); данные берутся из статиков {@link NowPlayingService}
 *       (тот же процесс), поэтому провайдер не держит своего состояния.</li>
 *   <li>{@code openFile(content://ru.big.town.anative.nowplaying/art)} → PNG обложки (FD на приватный
 *       файл Native; кросс-процессно отдаётся через ParcelFileDescriptor). {@code hasArt}/{@code updatedAt}
 *       из query позволяют UI понять, есть ли обложка и обновилась ли она (cache-busting).</li>
 * </ul>
 *
 * Exported=true (читает RestoreMode, отдельный /data-процесс). Данные не чувствительны (метаданные трека).
 */
public class NowPlayingProvider extends ContentProvider {

    private static final String TAG = "$$$ NowPlayingProvider $$$";
    private static final String KEYMANAGER_PACKAGE = "com.qinggan.keymanager.service";

    /** Synchronous command API used by the steering-wheel hook on the initial key DOWN only. */
    public static final String METHOD_MEDIA_COMMAND = "media_command";

    public static final String AUTHORITY = "ru.big.town.anative.nowplaying";
    public static final Uri CONTENT_URI  = Uri.parse("content://" + AUTHORITY);
    public static final Uri ART_URI      = Uri.parse("content://" + AUTHORITY + "/art");

    public static final String[] COLUMNS = {
            "title",     // 0
            "artist",    // 1
            "album",     // 2
            "package",   // 3
            "appLabel",  // 4
            "state",     // 5  PlaybackState.STATE_*
            "position",  // 6  мс
            "duration",  // 7  мс
            "hasArt",    // 8  0/1
            "updatedAt", // 9  epoch ms последнего обновления снимка
    };

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        c.addRow(new Object[]{
                NowPlayingService.sTitle,
                NowPlayingService.sArtist,
                NowPlayingService.sAlbum,
                NowPlayingService.sPackage,
                NowPlayingService.sAppLabel,
                NowPlayingService.sState,
                NowPlayingService.sPosition,
                NowPlayingService.sDuration,
                NowPlayingService.sHasArt ? 1 : 0,
                NowPlayingService.sUpdatedAt,
        });
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        File f = NowPlayingService.artFile(getContext());
        if (!f.exists()) throw new FileNotFoundException("no album art");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return uri != null && "art".equals(uri.getLastPathSegment())
                ? "image/png" : "vnd.android.cursor.item/nowplaying";
    }

    /**
     * Resolves and executes one media command against a fresh active-session snapshot.
     *
     * <p>The provider stays publicly readable for the existing now-playing consumers, therefore the
     * mutating {@code call()} entry point has its own strict caller check. Calling-package validation
     * happens before clearing Binder identity; the clear is required so MediaSessionManager checks
     * Native's privileged identity rather than keymanager's identity.</p>
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_MEDIA_COMMAND.equals(method)) return super.call(method, arg, extras);
        enforceMediaCommandCaller();

        MediaControlPolicy.Command command = parseCommand(arg);
        if (command == null) {
            Bundle result = new Bundle();
            result.putString("route", MediaControlRouter.ROUTE_NATIVE);
            result.putInt("keyCode", 0);
            result.putString("package", "");
            result.putInt("playbackClass", MediaControlPolicy.STATE_UNKNOWN);
            return result;
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return MediaControlRouter.dispatch(getContext(), command).toBundle();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void enforceMediaCommandCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return;
        String caller = null;
        try {
            caller = getCallingPackage();
        } catch (SecurityException e) {
            Log.w(TAG, "media_command: invalid calling package: " + e.getMessage());
        }
        if (!KEYMANAGER_PACKAGE.equals(caller)) {
            throw new SecurityException("media_command is not allowed for " + caller);
        }
    }

    private static MediaControlPolicy.Command parseCommand(String arg) {
        if ("play_pause".equals(arg)) return MediaControlPolicy.Command.PLAY_PAUSE;
        if ("pause_only".equals(arg)) return MediaControlPolicy.Command.PAUSE_ONLY;
        if ("next".equals(arg)) return MediaControlPolicy.Command.NEXT;
        if ("previous".equals(arg)) return MediaControlPolicy.Command.PREVIOUS;
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
