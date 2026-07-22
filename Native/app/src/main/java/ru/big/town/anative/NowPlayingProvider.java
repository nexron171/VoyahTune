package ru.big.town.anative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

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

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
