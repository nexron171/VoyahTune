package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Ридер «сейчас играет»: читает активную медиа-сессию ЛЮБОГО плеера (Яндекс.Музыка и т.п.) через
 * {@link MediaSessionManager} и публикует метаданные (название/исполнитель/альбом/состояние/позиция/
 * длительность/обложка) для наших поверхностей.
 *
 * <p><b>Почему это работает, а штатный виджет — нет:</b> сторонние плееры публикуют стандартную
 * {@code MediaSession}, но стоковый агрегатор головы (PAL {@code com.qinggan.media}/{@code tai.pal})
 * показывает только «свои» плееры. {@code MediaSessionManager} же видит ВСЕ сессии системно — нужен
 * лишь привилегированный {@code MEDIA_CONTENT_CONTROL} (Native — priv-app, разрешение в whitelist).
 * См. память reference_media_nowplaying_pipeline.
 *
 * <p><b>Публикация (два канала):</b>
 * <ul>
 *   <li>статический снимок читает {@link NowPlayingProvider} (pull: {@code content://…/nowplaying});
 *       обложка — файл {@link #artFile(Context)}, отдаётся провайдером через openFile;</li>
 *   <li>broadcast {@link #ACTION_NOW_PLAYING} с текстовыми extras (push: живое обновление UI).</li>
 * </ul>
 *
 * <p>Фича не завязана на Frida/VD — работает в обоих флейворах (Native priv-app и в full, и в light).
 */
public class NowPlayingService extends Service {

    private static final String TAG = "$$$ NowPlayingService $$$";
    private static final String CHANNEL_ID = "now_playing_channel";

    public static final String ACTION_NOW_PLAYING         = "ru.big.town.anative.NOW_PLAYING";
    public static final String ACTION_REQUEST_NOW_PLAYING = "ru.big.town.anative.REQUEST_NOW_PLAYING";

    private static final String ART_FILE_NAME = "nowplaying_art.png";

    // Маршрутизация медиа-кнопок руля. Решение принимает Native (здесь), потому что определить активную
    // медиа-сессию можно только через MediaSessionManager с MEDIA_CONTENT_CONTROL, а у процесса
    // keymanager (где живёт хук steeringwheelkeys.js) этой привилегии нет. Native знает топ-сессию (её же
    // он показывает как «сейчас играет») и публикует в Settings.Global строку-решение; хук читает её
    // синхронно при нажатии и роутит:
    //   "native"   → отдать клавишу штатной маршрутизации прошивки (BT/AVRCP, штатный плеер и его прокси,
    //                нет сессии, старт до готовности, нет привилегии) — стоковое поведение;
    //   "dispatch" → сторонний плеер (Яндекс/Spotify/…) → хук сам шлёт медиа-эвент в активную сессию.
    // Дефолт при отсутствии ключа — passthrough (см. хук), т.е. заводское поведение.
    static final String MEDIA_ROUTE_KEY = "voyahtune_mediaRoute";
    private static final String ROUTE_NATIVE   = "native";
    private static final String ROUTE_DISPATCH = "dispatch";

    // Текущий снимок — читает NowPlayingProvider (тот же процесс). volatile: пишет наш handler-тред,
    // читает binder-тред провайдера.
    static volatile String sTitle = "";
    static volatile String sArtist = "";
    static volatile String sAlbum = "";
    static volatile String sPackage = "";
    static volatile String sAppLabel = "";
    static volatile int  sState = PlaybackState.STATE_NONE;
    static volatile long sPosition = 0L;
    static volatile long sDuration = 0L;
    static volatile boolean sHasArt = false;
    static volatile long sUpdatedAt = 0L;

    /** Файл обложки (приватный для Native; наружу отдаётся через NowPlayingProvider.openFile). */
    static File artFile(Context ctx) {
        return new File(ctx.getFilesDir(), ART_FILE_NAME);
    }

    private Handler handler;
    private MediaSessionManager msm;
    private MediaController current;                 // сессия, за которой сейчас следим
    private MediaController.Callback controllerCallback;
    private String lastMediaRoute = null;            // последнее записанное решение (пишем только на смену)

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> { if (handler != null) handler.post(() -> onSessionsChanged(controllers)); };

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // UI открылось/подписалось — сразу отдать текущий снимок.
            if (handler != null) handler.post(() -> publish("request"));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        // Foreground обязателен (стартуем через startForegroundService). Не удалось поднять — тихо
        // гасим ТОЛЬКО этот сервис (stopSelf), НО не роняем процесс: иначе утащим за собой применение
        // режима на старте (ApplyEngine в том же процессе).
        try {
            createNotificationChannel();
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Медиа-информация")
                    .setContentText("Отслеживание текущего трека")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();
            startForeground(6, n);
        } catch (Exception e) {
            Log.e(TAG, "onCreate startForeground: " + e.getMessage());
            stopSelf();
            return;
        }
        try {
            registerReceiver(requestReceiver, new IntentFilter(ACTION_REQUEST_NOW_PLAYING), RECEIVER_EXPORTED);
        } catch (Exception e) {
            Log.w(TAG, "onCreate registerReceiver: " + e.getMessage());
        }
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            // null вместо NotificationListener-компонента разрешён при наличии MEDIA_CONTENT_CONTROL.
            msm.addOnActiveSessionsChangedListener(sessionsListener, null, handler);
            onSessionsChanged(msm.getActiveSessions(null));  // первичный снимок
            Log.i(TAG, "onCreate: подписка на активные медиа-сессии установлена");
        } catch (SecurityException e) {
            Log.e(TAG, "onCreate: нет MEDIA_CONTENT_CONTROL (whitelist на enforce-ROM?) — ридер инертен: "
                    + e.getMessage());
        } catch (Throwable e) {
            Log.e(TAG, "onCreate media listener: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Выбор активной сессии + подписка на её изменения
    // -------------------------------------------------------------------------

    /**
     * ВАЖНО: следим за playback ВСЕХ активных сессий, а не только выбранной.
     *
     * OnActiveSessionsChangedListener приходит на создание/уничтожение сессии и на setActive, но НЕ на
     * смену того, кто реально играет. Раньше мы подписывались только на выбранную сессию, поэтому
     * сценарий «играет Bluetooth → пользователь запускает Spotify» ломался: сессия Spotify появлялась
     * (колбэк был), но играть ещё не начинала, поэтому топ-сессией оставался BT. Когда Spotify начинал
     * играть, состав сессий не менялся — колбэка не было, current навсегда оставался на BT, а
     * voyahtune_mediaRoute залипал в "native". Кнопки руля уходили в штатный маршрут к мёртвой сессии:
     * первое нажатие ещё ставило паузу, дальше не работало ничего.
     */
    private final java.util.List<MediaController> watched = new java.util.ArrayList<>();
    private final java.util.List<MediaController.Callback> watchedCbs = new java.util.ArrayList<>();

    private void onSessionsChanged(List<MediaController> controllers) {
        detachAll();
        if (controllers != null) {
            for (MediaController c : controllers) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata metadata) { repick("metadata"); }
                    @Override public void onPlaybackStateChanged(PlaybackState state) { repick("playback"); }
                    @Override public void onSessionDestroyed() { handler.post(() -> onSessionsChanged(safeSessions())); }
                };
                try { c.registerCallback(cb, handler); watched.add(c); watchedCbs.add(cb); } catch (Exception ignored) {}
            }
        }
        current = pickController(controllers);
        Log.i(TAG, "сессий: " + (controllers == null ? 0 : controllers.size())
                + ", топ: " + (current != null ? current.getPackageName() : "нет"));
        publishMediaRoute();
        publish("sessions-changed");
    }

    /**
     * Перевыбор топ-сессии по СВЕЖЕМУ списку. Дёргается из playback/metadata-колбэков любой сессии —
     * именно здесь ловится «BT замолчал, заиграл Spotify», чего listener состава сессий не видит.
     * publishMediaRoute внутри дедуплицирует запись, так что Settings.Global не долбится.
     */
    private void repick(String reason) {
        MediaController pick = pickController(safeSessions());
        if (!sameController(pick, current)) {
            current = pick;
            Log.i(TAG, "топ-сессия сменилась (" + reason + "): "
                    + (current != null ? current.getPackageName() : "нет"));
        }
        publishMediaRoute();
        publish(reason);
    }

    private void detachAll() {
        for (int i = 0; i < watched.size(); i++) {
            try { watched.get(i).unregisterCallback(watchedCbs.get(i)); } catch (Exception ignored) {}
        }
        watched.clear();
        watchedCbs.clear();
        controllerCallback = null;
    }

    /**
     * Публикует в {@link #MEDIA_ROUTE_KEY} решение о маршрутизации медиа-кнопок руля по ТЕКУЩЕЙ топ-сессии
     * (её же читает {@code dispatchMediaKeyEvent}). Пишем ТОЛЬКО на смену решения — иначе долбили бы
     * Settings.Global (playback-колбэки идут десятками в секунду). Нет сессии / OEM-пакет → штатная
     * маршрутизация; сторонний плеер → перехват.
     */
    private void publishMediaRoute() {
        String pkg = (current != null) ? nz(current.getPackageName()) : "";
        String route = (pkg.isEmpty() || isOemMediaPackage(pkg)) ? ROUTE_NATIVE : ROUTE_DISPATCH;
        if (route.equals(lastMediaRoute)) return;   // без изменений — не трогаем Settings.Global
        lastMediaRoute = route;
        try {
            android.provider.Settings.Global.putString(getContentResolver(), MEDIA_ROUTE_KEY, route);
            Log.i(TAG, "mediaRoute → " + route + " (pkg=" + (pkg.isEmpty() ? "none" : pkg) + ")");
        } catch (Exception e) {
            // Нет WRITE_SECURE_SETTINGS (enforce-ROM без whitelist) → ключ не появится → хук по умолчанию
            // passthrough (стоковое поведение). Безопасная деградация.
            Log.w(TAG, "publishMediaRoute: " + e.getMessage());
        }
    }

    /**
     * OEM/системная медиа-сессия, которую корректно рулит штатная маршрутизация прошивки: Bluetooth/AVRCP
     * (телефон), штатный плеер {@code com.qinggan.media} и его прокси/зеркала. Всё остальное — сторонние
     * приложения (Яндекс.Музыка/Spotify/…), которыми штатная маршрутизация может не управлять, поэтому их
     * перехватываем и шлём сами.
     */
    private static boolean isOemMediaPackage(String pkg) {
        return pkg.startsWith("com.android.") || pkg.startsWith("com.qinggan.") || pkg.equals("android");
    }

    /** Кандидат: первый ИГРАЮЩИЙ; если никто не играет — первый из списка (он в порядке приоритета). */
    private MediaController pickController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return null;
        for (MediaController c : controllers) {
            PlaybackState ps = c.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return controllers.get(0);
    }

    private List<MediaController> safeSessions() {
        try { return msm.getActiveSessions(null); } catch (Exception e) { return null; }
    }

    private boolean sameController(MediaController a, MediaController b) {
        if (a == null || b == null) return a == b;
        try { return a.getSessionToken().equals(b.getSessionToken()); } catch (Exception e) { return false; }
    }

    /** Снять подписки со всех отслеживаемых сессий (следим за всеми, а не только за выбранной). */
    private void detachCurrent() {
        detachAll();
    }

    // -------------------------------------------------------------------------
    // Публикация снимка (статик для провайдера + broadcast для UI)
    // -------------------------------------------------------------------------

    private void publish(String reason) {
        try {
            String title = "", artist = "", album = "", pkg = "", appLabel = "";
            int state = PlaybackState.STATE_NONE;
            long position = 0L, duration = 0L;
            boolean hasArt = false;

            if (current != null) {
                pkg = nz(current.getPackageName());
                appLabel = appLabel(pkg);
                MediaMetadata md = current.getMetadata();
                if (md != null) {
                    title  = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                                           md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    artist = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                           md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                                           md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    album  = nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    hasArt = writeArt(md);
                }
                PlaybackState ps = current.getPlaybackState();
                if (ps != null) { state = ps.getState(); position = ps.getPosition(); }
            }

            sTitle = title; sArtist = artist; sAlbum = album; sPackage = pkg; sAppLabel = appLabel;
            sState = state; sPosition = position; sDuration = duration; sHasArt = hasArt;
            sUpdatedAt = System.currentTimeMillis();

            Intent i = new Intent(ACTION_NOW_PLAYING);
            i.setPackage(null);  // broadcast всем нашим подписчикам (VoyahTune и др.)
            i.putExtra("title", title);
            i.putExtra("artist", artist);
            i.putExtra("album", album);
            i.putExtra("package", pkg);
            i.putExtra("appLabel", appLabel);
            i.putExtra("state", state);
            i.putExtra("position", position);
            i.putExtra("duration", duration);
            i.putExtra("hasArt", hasArt);
            i.putExtra("updatedAt", sUpdatedAt);
            sendBroadcast(i);

            Log.i(TAG, "publish(" + reason + "): [" + pkg + "] " + title + " — " + artist
                    + " state=" + state + " art=" + hasArt);
        } catch (Exception e) {
            Log.w(TAG, "publish: " + e.getMessage());
        }
    }

    /** Сохраняет обложку в приватный файл (отдаётся наружу через NowPlayingProvider). @return есть ли обложка. */
    private boolean writeArt(MediaMetadata md) {
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = artFile(this);
        if (bmp == null) { if (f.exists()) f.delete(); return false; }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "writeArt: " + e.getMessage());
            return false;
        }
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        // Сервис уходит → трекер сессий мёртв. Сбрасываем маршрут в безопасный passthrough, чтобы застрявшее
        // "dispatch" не роняло медиа-кнопки, если сторонний плеер к тому моменту уже остановлен.
        try { android.provider.Settings.Global.putString(getContentResolver(), MEDIA_ROUTE_KEY, ROUTE_NATIVE); }
        catch (Exception ignored) {}
        detachCurrent();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Медиа-информация",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
