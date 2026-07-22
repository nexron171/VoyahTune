package ru.big.town.restoremode;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.PlaybackState;
import android.net.Uri;

import java.io.InputStream;

/**
 * Клиент «сейчас играет» — единая точка доступа UI VoyahTune к метаданным текущего трека,
 * которые публикует Native ({@code NowPlayingService}/{@code NowPlayingProvider}).
 *
 * <p>Две модели использования:
 * <ul>
 *   <li><b>pull:</b> {@link #query(Context)} — прочитать снимок сейчас (напр. при открытии экрана);
 *       {@link #loadArt(Context)} — подгрузить обложку;</li>
 *   <li><b>push:</b> подписаться на broadcast {@link #ACTION_NOW_PLAYING} (extras те же поля) для живого
 *       обновления; при подписке вызвать {@link #requestRefresh(Context)}, чтобы Native сразу отдал текущий
 *       снимок.</li>
 * </ul>
 * Ничего не завязано на флейвор — работает и в full, и в light (Native priv-app в обоих).
 */
public final class NowPlayingClient {

    public static final String ACTION_NOW_PLAYING         = "ru.big.town.anative.NOW_PLAYING";
    public static final String ACTION_REQUEST_NOW_PLAYING = "ru.big.town.anative.REQUEST_NOW_PLAYING";

    private static final String AUTHORITY = "ru.big.town.anative.nowplaying";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri ART_URI     = Uri.parse("content://" + AUTHORITY + "/art");

    private NowPlayingClient() {}

    /** Снимок текущего трека. Пустой title = ничего не играет / нет данных. */
    public static final class NowPlaying {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String packageName = "";
        public String appLabel = "";
        public int  state = PlaybackState.STATE_NONE;
        public long position = 0L;
        public long duration = 0L;
        public boolean hasArt = false;
        public long updatedAt = 0L;

        public boolean isPlaying() { return state == PlaybackState.STATE_PLAYING; }
        public boolean isEmpty()   { return title == null || title.isEmpty(); }
    }

    /** Читает снимок из провайдера Native. Возвращает пустой {@link NowPlaying} при недоступности. */
    public static NowPlaying query(Context ctx) {
        NowPlaying np = new NowPlaying();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CONTENT_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                np.title       = str(c, "title");
                np.artist      = str(c, "artist");
                np.album       = str(c, "album");
                np.packageName = str(c, "package");
                np.appLabel    = str(c, "appLabel");
                np.state       = intOf(c, "state", PlaybackState.STATE_NONE);
                np.position    = longOf(c, "position");
                np.duration    = longOf(c, "duration");
                np.hasArt      = intOf(c, "hasArt", 0) == 1;
                np.updatedAt   = longOf(c, "updatedAt");
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return np;
    }

    /** Снимок из extras broadcast'а {@link #ACTION_NOW_PLAYING} (без запроса к провайдеру). */
    public static NowPlaying fromBroadcast(Intent i) {
        NowPlaying np = new NowPlaying();
        if (i == null) return np;
        np.title       = nz(i.getStringExtra("title"));
        np.artist      = nz(i.getStringExtra("artist"));
        np.album       = nz(i.getStringExtra("album"));
        np.packageName = nz(i.getStringExtra("package"));
        np.appLabel    = nz(i.getStringExtra("appLabel"));
        np.state       = i.getIntExtra("state", PlaybackState.STATE_NONE);
        np.position    = i.getLongExtra("position", 0L);
        np.duration    = i.getLongExtra("duration", 0L);
        np.hasArt      = i.getBooleanExtra("hasArt", false);
        np.updatedAt   = i.getLongExtra("updatedAt", 0L);
        return np;
    }

    /** Подгружает обложку из провайдера Native. null, если обложки нет. */
    public static Bitmap loadArt(Context ctx) {
        InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(ART_URI);
            return (is != null) ? BitmapFactory.decodeStream(is) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    /** Просит Native немедленно опубликовать текущий снимок (broadcast придёт слушателям). */
    public static void requestRefresh(Context ctx) {
        try {
            Intent i = new Intent(ACTION_REQUEST_NOW_PLAYING);
            i.setPackage("ru.big.town.anative");
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // -- helpers --
    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? nz(c.getString(i)) : "";
    }
    private static int intOf(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getInt(i) : def;
    }
    private static long longOf(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getLong(i) : 0L;
    }
    private static String nz(String s) { return s == null ? "" : s; }
}
