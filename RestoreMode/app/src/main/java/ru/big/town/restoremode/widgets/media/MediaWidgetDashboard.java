package ru.big.town.restoremode.widgets.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.integration.media.NowPlayingClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Owns the “now playing” snapshot and the media source catalog shared by every media card. */
public final class MediaWidgetDashboard {
    private static final long TICK_MS = 1000L;
    private static final String ACTION_SET_MEDIA_AUTOSTART_PLAYER =
            "ru.big.town.anative.SET_MEDIA_AUTOSTART_PLAYER";
    private static final String BIND_SET_MODES_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    private final Context context;
    private final Consumer<String> notify;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaWidgetView> views = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new ConcurrentHashMap<>();
    private final ExecutorService artExecutor = newExecutor("VoyahTune-media-art");
    private final ExecutorService sourceExecutor = newExecutor("VoyahTune-media-sources");

    private NowPlayingClient.NowPlaying snapshot = new NowPlayingClient.NowPlaying();
    private List<MediaSource> sources = Collections.emptyList();
    private boolean sourcesLoaded;
    private boolean sourcesLoading;
    private Bitmap art;
    private String requestedArtKey = "";
    private Bitmap sourceIcon;
    private String sourceIconPackage = "";
    private boolean running;
    private boolean destroyed;

    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (destroyed) {
                        return;
                    }
                    applySnapshot(NowPlayingClient.fromBroadcast(intent));
                }
            };

    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    if (!running || destroyed) {
                        return;
                    }
                    redraw();
                    handler.postDelayed(this, TICK_MS);
                }
            };

    public MediaWidgetDashboard(Context context, Consumer<String> notify) {
        this.context = context;
        this.notify = notify;
    }

    public View create() {
        MediaWidgetView view = new MediaWidgetView(context, new ViewListener());
        views.add(view);
        view.setSources(sourcesLoaded ? sources : null);
        view.setSourceIcon(sourceIcon);
        view.update(snapshot, art);
        return view;
    }

    public void resetViews() {
        views.clear();
    }

    public void setActive(boolean active) {
        boolean shouldRun = active && !destroyed && !views.isEmpty();
        if (shouldRun == running) {
            return;
        }
        running = shouldRun;
        handler.removeCallbacks(tick);
        if (!running) {
            unregisterReceiver();
            return;
        }
        registerReceiver();
        NowPlayingClient.requestRefresh(context);
        applySnapshot(NowPlayingClient.query(context));
        loadSources();
        handler.post(tick);
    }

    private void registerReceiver() {
        try {
            context.registerReceiver(
                    receiver,
                    new IntentFilter(NowPlayingClient.ACTION_NOW_PLAYING),
                    null,
                    null,
                    Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {
        }
    }

    private void unregisterReceiver() {
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    private void applySnapshot(NowPlayingClient.NowPlaying next) {
        snapshot = next == null ? new NowPlayingClient.NowPlaying() : next;
        String artKey = artKey(snapshot);
        if (!artKey.equals(requestedArtKey)) {
            requestedArtKey = artKey;
            if (artKey.isEmpty()) {
                art = null;
            } else {
                loadArt(artKey);
            }
        }
        loadSourceIcon();
        redraw();
    }

    private void loadArt(String key) {
        try {
            artExecutor.execute(
                    () -> {
                        Bitmap loaded = NowPlayingClient.loadArt(context);
                        handler.post(
                                () -> {
                                    if (destroyed || !key.equals(requestedArtKey)) {
                                        return;
                                    }
                                    art = loaded;
                                    redraw();
                                });
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void loadSources() {
        if (sourcesLoading || destroyed) {
            return;
        }
        sourcesLoading = true;
        try {
            sourceExecutor.execute(
                    () -> {
                        List<MediaSource> loaded = MediaSourceCatalog.load(context, iconCache);
                        handler.post(
                                () -> {
                                    sourcesLoading = false;
                                    if (destroyed) {
                                        return;
                                    }
                                    sources = loaded;
                                    sourcesLoaded = true;
                                    for (MediaWidgetView view : views) {
                                        view.setSources(loaded);
                                    }
                                    applyCatalogIcon();
                                });
                    });
        } catch (RejectedExecutionException ignored) {
            sourcesLoading = false;
        }
    }

    private void loadSourceIcon() {
        String packageName = snapshot.isEmpty() ? "" : snapshot.packageName;
        if (packageName.equals(sourceIconPackage)) {
            return;
        }
        sourceIconPackage = packageName;
        if (packageName.isEmpty()) {
            updateSourceIcon(null);
            return;
        }
        MediaSource known = findSource(packageName);
        if (known != null) {
            updateSourceIcon(known.icon);
            return;
        }
        Bitmap cached = iconCache.get(packageName);
        if (cached != null) {
            updateSourceIcon(cached);
            return;
        }
        updateSourceIcon(null);
        try {
            sourceExecutor.execute(
                    () -> {
                        Bitmap icon =
                                MediaSourceCatalog.icon(
                                        context.getPackageManager(), packageName, iconCache);
                        handler.post(
                                () -> {
                                    if (destroyed || !packageName.equals(sourceIconPackage)) {
                                        return;
                                    }
                                    updateSourceIcon(icon);
                                });
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void applyCatalogIcon() {
        String packageName = sourceIconPackage;
        if (packageName.isEmpty()) {
            return;
        }
        MediaSource known = findSource(packageName);
        if (known != null && known.icon != null && known.icon != sourceIcon) {
            updateSourceIcon(known.icon);
        }
    }

    private void updateSourceIcon(Bitmap icon) {
        sourceIcon = icon;
        for (MediaWidgetView view : views) {
            view.setSourceIcon(icon);
        }
    }

    private MediaSource findSource(String packageName) {
        for (MediaSource source : sources) {
            if (source.packageName.equals(packageName)) {
                return source;
            }
        }
        return null;
    }

    private void redraw() {
        for (MediaWidgetView view : views) {
            view.update(snapshot, art);
        }
    }

    private void command(MediaWidgetView.Command command) {
        String value =
                command == MediaWidgetView.Command.PREVIOUS
                        ? NowPlayingClient.COMMAND_PREVIOUS
                        : command == MediaWidgetView.Command.NEXT
                                ? NowPlayingClient.COMMAND_NEXT
                                : NowPlayingClient.COMMAND_PLAY_PAUSE;
        if (!NowPlayingClient.sendCommand(context, value) && !destroyed) {
            notify.accept("Плеер не ответил на команду");
        }
    }

    private void openPlayer() {
        String packageName = snapshot.packageName;
        if (packageName.isEmpty()) {
            return;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            notify.accept("Не удалось открыть плеер");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            notify.accept("Не удалось открыть плеер");
        }
    }

    private void chooseSource(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Intent request =
                new Intent(ACTION_SET_MEDIA_AUTOSTART_PLAYER)
                        .setClassName(
                                "ru.big.town.anative",
                                "ru.big.town.anative.SetModesReceiverDynamic")
                        .putExtra("pkg", packageName)
                        .putExtra("display", DashboardProfile.displayId(context));
        try {
            context.sendBroadcast(request, BIND_SET_MODES_PERMISSION);
            notify.accept("Запускаем выбранный источник");
        } catch (RuntimeException error) {
            notify.accept("Не удалось запустить источник");
        }
    }

    private static ExecutorService newExecutor(String name) {
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static String artKey(NowPlayingClient.NowPlaying value) {
        if (value == null || !value.hasArt) {
            return "";
        }
        return value.packageName
                + '|'
                + value.title
                + '|'
                + value.artist
                + '|'
                + value.album;
    }

    public void close() {
        destroyed = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver();
        artExecutor.shutdownNow();
        sourceExecutor.shutdownNow();
        views.clear();
        iconCache.clear();
        art = null;
        sourceIcon = null;
        sources = Collections.emptyList();
        sourcesLoaded = false;
    }

    private final class ViewListener implements MediaWidgetView.Listener {
        @Override
        public void onCommand(MediaWidgetView.Command command) {
            MediaWidgetDashboard.this.command(command);
        }

        @Override
        public void onOpenPlayer() {
            openPlayer();
        }

        @Override
        public void onSourceChosen(String packageName) {
            chooseSource(packageName);
        }

        @Override
        public void onSourceChooserOpened() {
            loadSources();
        }
    }
}
