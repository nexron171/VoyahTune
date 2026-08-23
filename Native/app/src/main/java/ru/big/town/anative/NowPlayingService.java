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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    // Legacy-маршрут для совместимости со старыми версиями steeringwheelkeys.js. Новый хук на каждое
    // initial DOWN синхронно вызывает NowPlayingProvider.media_command: там берётся СВЕЖИЙ список сессий,
    // выбирается конкретная цель и команда доставляется ровно одним путём. Старый Settings.Global ключ
    // продолжаем публиковать, чтобы обновление APK отдельно от Packaging не ломало кнопки:
    //   "native"   → отдать клавишу штатной маршрутизации прошивки (BT/AVRCP, штатный плеер и его прокси,
    //                нет сессии, старт до готовности, нет привилегии) — стоковое поведение;
    //   "dispatch" → сторонний плеер (Яндекс/Spotify/…) → хук сам шлёт медиа-эвент в активную сессию.
    // Дефолт при отсутствии ключа — passthrough (см. хук), т.е. заводское поведение.
    static final String MEDIA_ROUTE_KEY = "voyahtune_mediaRoute";
    private static final String ROUTE_NATIVE   = "native";
    private static final String ROUTE_DISPATCH = "dispatch";
    private static final AtomicLong INSTANCE_SEQUENCE = new AtomicLong();
    private static final AtomicLong ACTIVE_INSTANCE = new AtomicLong();
    private static final AtomicLong ROUTE_REVISION = new AtomicLong();
    private static final AtomicLong BROADCAST_REVISION = new AtomicLong();
    private static final Object SNAPSHOT_COMMIT_LOCK = new Object();
    private static final Object ART_COMMIT_LOCK = new Object();
    private static final ThreadPoolExecutor ROUTE_EXECUTOR = newDeliveryExecutor("MediaRoute");
    private static final ThreadPoolExecutor BROADCAST_EXECUTOR =
            newDeliveryExecutor("NowPlayingBroadcast");
    private static final LatestValueDelivery<RouteWrite> ROUTE_WRITES =
            new LatestValueDelivery<>(ROUTE_EXECUTOR, NowPlayingService::writeRoute);
    private static final LatestValueDelivery<BroadcastWrite> BROADCASTS =
            new LatestValueDelivery<>(BROADCAST_EXECUTOR, NowPlayingService::sendSnapshotBroadcast);
    // Confined to ROUTE_EXECUTOR.
    private static long lastWrittenRouteGeneration;
    private static String lastWrittenRoute = "";

    private static ThreadPoolExecutor newDeliveryExecutor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class RouteWrite {
        final Context app;
        final long generation;
        final String route;
        final String pkg;
        final boolean clearGeneration;

        RouteWrite(Context app, long generation, String route, String pkg,
                   boolean clearGeneration) {
            this.app = app;
            this.generation = generation;
            this.route = route;
            this.pkg = pkg;
            this.clearGeneration = clearGeneration;
        }
    }

    private static final class BroadcastWrite {
        final Context app;
        final long generation;
        final Intent intent;

        BroadcastWrite(Context app, long generation, Intent intent) {
            this.app = app;
            this.generation = generation;
            this.intent = intent;
        }
    }

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

    private volatile Handler handler;
    private HandlerThread workerThread;
    private volatile boolean stopping;
    private long instanceGeneration;
    private boolean receiverRegistered;
    private MediaSessionManager msm;
    private MediaController current;                 // сессия, за которой сейчас следим
    private MediaController.Callback controllerCallback;
    private Bitmap lastWrittenArt;                   // тот же Bitmap не кодируем в PNG на каждый playback callback

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> dispatchWorker("sessions", () -> onSessionsChanged(controllers));

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // UI открылось/подписалось — сразу отдать текущий снимок.
            dispatchWorker("request", () -> publish("request"));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Сначала fail-closed сбрасываем legacy-маршрут. Даже если foreground-уведомление не
        // поднимется, старое значение "dispatch" не должно остаться после неудачного запуска.
        instanceGeneration = INSTANCE_SEQUENCE.incrementAndGet();
        ACTIVE_INSTANCE.set(instanceGeneration);
        resetSnapshotForNewInstance();
        enqueueRoute(ROUTE_NATIVE, "startup", false);
        enqueueSnapshotBroadcast(buildSnapshotIntent());
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
        workerThread = new HandlerThread("NowPlaying", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        dispatchWorker("initialize", () -> {
            try {
                registerReceiver(requestReceiver,
                        new IntentFilter(ACTION_REQUEST_NOW_PLAYING), null, handler,
                        RECEIVER_EXPORTED);
                receiverRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "onCreate registerReceiver: " + e.getMessage());
            }
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            try {
                // null вместо NotificationListener-компонента разрешён при MEDIA_CONTENT_CONTROL.
                msm.addOnActiveSessionsChangedListener(sessionsListener, null, handler);
                onSessionsChanged(msm.getActiveSessions(null));  // первичный снимок
                Log.i(TAG, "onCreate: подписка на активные медиа-сессии установлена");
            } catch (SecurityException e) {
                Log.e(TAG, "onCreate: нет MEDIA_CONTENT_CONTROL (whitelist на enforce-ROM?) — ридер инертен: "
                        + e.getMessage());
            }
        });
    }

    private boolean dispatchWorker(String source, Runnable action) {
        Handler worker = handler;
        if (stopping || worker == null) return false;
        return worker.post(() -> {
            if (stopping) return;
            try {
                action.run();
            } catch (Throwable e) {
                Log.e(TAG, source + ": " + e.getMessage(), e);
            }
        });
    }

    private boolean isActiveInstance() {
        return !stopping && ACTIVE_INSTANCE.get() == instanceGeneration;
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
        if (stopping) return;
        detachAll();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (stopping) return;
                final MediaController watchedController = c;
                MediaController.Callback cb = new MediaController.Callback() {
                    private boolean wasActive = MediaControlRouter.isActiveState(
                            safePlaybackState(watchedController));

                    @Override public void onMetadataChanged(MediaMetadata metadata) {
                        dispatchWorker("metadata", () -> repick("metadata"));
                    }
                    @Override public void onPlaybackStateChanged(PlaybackState state) {
                        if (stopping) return;
                        boolean active = MediaControlRouter.isActiveState(state);
                        if (active && !wasActive) MediaControlRouter.notePlaying(watchedController);
                        wasActive = active;
                        dispatchWorker("playback", () -> repick("playback"));
                    }
                    @Override public void onSessionDestroyed() {
                        dispatchWorker("session-destroyed",
                                () -> onSessionsChanged(safeSessions()));
                    }
                };
                try { c.registerCallback(cb, handler); watched.add(c); watchedCbs.add(cb); } catch (Exception ignored) {}
            }
        }
        current = MediaControlRouter.selectController(controllers);
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
        if (stopping) return;
        MediaController pick = MediaControlRouter.selectController(safeSessions());
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
        enqueueRoute(route, pkg.isEmpty() ? "none" : pkg, false);
    }

    private void enqueueRoute(String route, String pkg, boolean clearGeneration) {
        if (instanceGeneration == 0L) return;
        Context app = getApplicationContext();
        // Terminal token is greater than every normal token of this service instance. Therefore
        // an operation which entered before onDestroy and returned from Binder later cannot replace
        // the queued fail-closed ROUTE_NATIVE write. The next instance still has a greater token.
        long deliveryToken = (instanceGeneration << 1) | (clearGeneration ? 1L : 0L);
        ROUTE_WRITES.offer(deliveryToken, ROUTE_REVISION.incrementAndGet(),
                new RouteWrite(app, instanceGeneration, route, pkg, clearGeneration));
    }

    private static void writeRoute(RouteWrite request) {
        if (request == null || ACTIVE_INSTANCE.get() != request.generation) return;
        try {
            if (lastWrittenRouteGeneration != request.generation
                    || !request.route.equals(lastWrittenRoute)) {
                boolean written = android.provider.Settings.Global.putString(
                        request.app.getContentResolver(), MEDIA_ROUTE_KEY, request.route);
                if (written) {
                    lastWrittenRouteGeneration = request.generation;
                    lastWrittenRoute = request.route;
                    Log.i(TAG, "mediaRoute → " + request.route
                            + " (pkg=" + request.pkg + ")");
                } else {
                    Log.w(TAG, "publishMediaRoute: Settings.Global rejected write");
                }
            }
        } catch (Exception e) {
            // Нет WRITE_SECURE_SETTINGS (enforce-ROM без whitelist) → ключ не появится → хук по умолчанию
            // passthrough (стоковое поведение). Безопасная деградация.
            Log.w(TAG, "publishMediaRoute: " + e.getMessage());
        } finally {
            if (request.clearGeneration) {
                ACTIVE_INSTANCE.compareAndSet(request.generation, 0L);
            }
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

    private List<MediaController> safeSessions() {
        try { return msm.getActiveSessions(null); } catch (Exception e) { return null; }
    }

    private static PlaybackState safePlaybackState(MediaController controller) {
        try { return controller == null ? null : controller.getPlaybackState(); }
        catch (Exception e) { return null; }
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
        if (!isActiveInstance()) return;
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

            synchronized (SNAPSHOT_COMMIT_LOCK) {
                if (!isActiveInstance()) return;
                sTitle = title; sArtist = artist; sAlbum = album;
                sPackage = pkg; sAppLabel = appLabel;
                sState = state; sPosition = position; sDuration = duration; sHasArt = hasArt;
                sUpdatedAt = System.currentTimeMillis();
            }
            enqueueSnapshotBroadcast(buildSnapshotIntent());

            Log.i(TAG, "publish(" + reason + "): [" + pkg + "] " + title + " — " + artist
                    + " state=" + state + " art=" + hasArt);
        } catch (Exception e) {
            Log.w(TAG, "publish: " + e.getMessage());
        }
    }

    private void resetSnapshotForNewInstance() {
        synchronized (SNAPSHOT_COMMIT_LOCK) {
            sTitle = "";
            sArtist = "";
            sAlbum = "";
            sPackage = "";
            sAppLabel = "";
            sState = PlaybackState.STATE_NONE;
            sPosition = 0L;
            sDuration = 0L;
            sHasArt = false;
            sUpdatedAt = System.currentTimeMillis();
        }
    }

    private static Intent buildSnapshotIntent() {
        synchronized (SNAPSHOT_COMMIT_LOCK) {
            Intent intent = new Intent(ACTION_NOW_PLAYING);
            intent.setPackage(null);
            intent.putExtra("title", sTitle);
            intent.putExtra("artist", sArtist);
            intent.putExtra("album", sAlbum);
            intent.putExtra("package", sPackage);
            intent.putExtra("appLabel", sAppLabel);
            intent.putExtra("state", sState);
            intent.putExtra("position", sPosition);
            intent.putExtra("duration", sDuration);
            intent.putExtra("hasArt", sHasArt);
            intent.putExtra("updatedAt", sUpdatedAt);
            return intent;
        }
    }

    private void enqueueSnapshotBroadcast(Intent intent) {
        if (instanceGeneration == 0L || intent == null) return;
        Context app = getApplicationContext();
        BROADCASTS.offer(instanceGeneration, BROADCAST_REVISION.incrementAndGet(),
                new BroadcastWrite(app, instanceGeneration, intent));
    }

    private static void sendSnapshotBroadcast(BroadcastWrite request) {
        if (request == null || ACTIVE_INSTANCE.get() != request.generation) return;
        request.app.sendBroadcast(request.intent);
    }

    /** Сохраняет обложку в приватный файл (отдаётся наружу через NowPlayingProvider). @return есть ли обложка. */
    private boolean writeArt(MediaMetadata md) {
        if (!isActiveInstance()) return false;
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = artFile(this);
        if (bmp == null) {
            lastWrittenArt = null;
            synchronized (ART_COMMIT_LOCK) {
                if (isActiveInstance() && f.exists()) f.delete();
            }
            return false;
        }
        // PlaybackState может приходить много раз в секунду с тем же объектом MediaMetadata/Bitmap.
        // PNG-compress + перезапись файла нужны только при реальной смене обложки.
        if (bmp == lastWrittenArt && f.exists() && f.length() > 0L) {
            return isActiveInstance();
        }
        File pending = new File(getFilesDir(), ART_FILE_NAME + "." + instanceGeneration + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(pending)) {
            boolean written = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            if (!written) {
                pending.delete();
                return false;
            }
            synchronized (ART_COMMIT_LOCK) {
                if (!isActiveInstance()) {
                    pending.delete();
                    return false;
                }
                Os.rename(pending.getAbsolutePath(), f.getAbsolutePath());
                lastWrittenArt = bmp;
                return true;
            }
        } catch (Exception e) {
            pending.delete();
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
        stopping = true;
        Handler worker = handler;
        HandlerThread thread = workerThread;
        enqueueRoute(ROUTE_NATIVE, "destroy", true);
        if (worker != null && thread != null) {
            boolean queued = worker.postAtFrontOfQueue(() -> {
                try {
                    if (receiverRegistered) {
                        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
                        receiverRegistered = false;
                    }
                    if (msm != null) {
                        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); }
                        catch (Exception ignored) {}
                    }
                    detachCurrent();
                } finally {
                    worker.removeCallbacksAndMessages(null);
                    handler = null;
                    thread.quitSafely();
                }
            });
            if (!queued) thread.quitSafely();
        }
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
