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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Owns the “now playing” snapshot and shares it with every visible media card. */
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
    private final ExecutorService artExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "VoyahTune-media-art");
                        thread.setDaemon(true);
                        return thread;
                    });

    private NowPlayingClient.NowPlaying snapshot = new NowPlayingClient.NowPlaying();
    private Bitmap art;
    private String requestedArtKey = "";
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

    private void chooseSource() {
        MediaPlayerPicker.show(
                context,
                packageName -> {
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
        views.clear();
        art = null;
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
        public void onChooseSource() {
            chooseSource();
        }
    }
}
