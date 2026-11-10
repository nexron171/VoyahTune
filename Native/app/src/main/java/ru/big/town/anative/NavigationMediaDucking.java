package ru.big.town.anative;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** One navigation gain owner, independent of OEM volume and each player's own gain. */
final class NavigationMediaDucking implements AutoCloseable {
    private static final String TAG = "NavigationMedia";
    private static final int SHAPER_ID = 0x5654;
    private final AudioManager audio;
    private final HandlerThread thread = new HandlerThread(TAG);
    private final Handler worker;
    private final NavigationMediaDuckingState state = new NavigationMediaDuckingState(this::report);
    private final Runnable refresh = this::refresh;
    private Method packageName;
    private Method clientUid;
    private Method playerId;
    private Method isActive;
    private Method playerProxy;
    private Method applyShape;
    private VolumeShaper.Configuration shape;
    private VolumeShaper.Configuration shapeId;
    private VolumeShaper.Operation play;
    private boolean watching;
    private boolean closed;
    private boolean reportedFailure;

    private final AudioManager.AudioPlaybackCallback callback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configurations) {
                    refresh();
                }
            };

    NavigationMediaDucking(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::recover);
    }

    void setNavigationActive(String owner, boolean active) {
        worker.post(
                () -> {
                    if (closed) return;
                    state.setNavigationActive(owner, active);
                    refresh();
                });
    }

    private void resolve() throws Exception {
        if (shape != null) return;
        packageName = AudioPlaybackConfiguration.class.getMethod("getPackageName");
        clientUid = AudioPlaybackConfiguration.class.getMethod("getClientUid");
        playerId = AudioPlaybackConfiguration.class.getMethod("getPlayerInterfaceId");
        isActive = AudioPlaybackConfiguration.class.getMethod("isActive");
        playerProxy = AudioPlaybackConfiguration.class.getMethod("getPlayerProxy");
        applyShape =
                Class.forName("android.media.PlayerProxy")
                        .getMethod(
                                "applyVolumeShaper",
                                VolumeShaper.Configuration.class,
                                VolumeShaper.Operation.class);
        VolumeShaper.Configuration.Builder configuration =
                new VolumeShaper.Configuration.Builder()
                        .setCurve(new float[] {0, 1}, new float[] {1, 0.2f})
                        .setDuration(150);
        configuration.getClass().getMethod("setId", int.class).invoke(configuration, SHAPER_ID);
        configuration.getClass().getMethod("setOptionFlags", int.class).invoke(configuration, 2);
        VolumeShaper.Configuration nextShape = configuration.build();
        shapeId = VolumeShaper.Configuration.class.getConstructor(int.class).newInstance(SHAPER_ID);
        Class<?> operationBuilder = Class.forName("android.media.VolumeShaper$Operation$Builder");
        Object operation =
                operationBuilder
                        .getConstructor(VolumeShaper.Operation.class)
                        .newInstance(VolumeShaper.Operation.PLAY);
        operationBuilder.getMethod("createIfNeeded").invoke(operation);
        play = (VolumeShaper.Operation) operationBuilder.getMethod("build").invoke(operation);
        shape = nextShape;
    }

    private void refresh() {
        worker.removeCallbacks(refresh);
        if (closed) return;
        try {
            if (state.needsPlayback()) {
                resolve();
                if (!watching) {
                    audio.registerAudioPlaybackCallback(callback, worker);
                    watching = true;
                }
                state.updatePlayers(playbackPlayers());
            }
        } catch (Exception failure) {
            report(failure);
        }
        if (state.needsPlayback()) {
            worker.postDelayed(refresh, 250);
        } else if (watching) {
            audio.unregisterAudioPlaybackCallback(callback);
            watching = false;
        }
    }

    private List<NavigationMediaDuckingState.Player> playbackPlayers() throws Exception {
        List<NavigationMediaDuckingState.Player> players = new ArrayList<>();
        for (AudioPlaybackConfiguration configuration : audio.getActivePlaybackConfigurations()) {
            Object proxy = playerProxy.invoke(configuration);
            if (proxy != null) {
                players.add(
                        new PlaybackPlayer(
                                (Integer) playerId.invoke(configuration),
                                Boolean.TRUE.equals(isActive.invoke(configuration)),
                                (String) packageName.invoke(configuration),
                                (Integer) clientUid.invoke(configuration),
                                configuration.getAudioAttributes().getVolumeControlStream(),
                                configuration.getAudioAttributes().getUsage(),
                                configuration.getAudioAttributes().getContentType(),
                                proxy));
            }
        }
        return players;
    }

    private void recover() {
        try {
            resolve();
            for (NavigationMediaDuckingState.Player player : playbackPlayers()) {
                try {
                    player.restore();
                } catch (Exception missingShape) {
                    // Most players have no private shaper; only an interrupted Native owns one.
                }
            }
        } catch (Exception failure) {
            report(failure);
        }
    }

    private final class PlaybackPlayer implements NavigationMediaDuckingState.Player {
        private final int id;
        private final boolean active;
        private final String packageName;
        private final int uid;
        private final int stream;
        private final int usage;
        private final int content;
        private final Object proxy;

        PlaybackPlayer(
                int id,
                boolean active,
                String packageName,
                int uid,
                int stream,
                int usage,
                int content,
                Object proxy) {
            this.id = id;
            this.active = active;
            this.packageName = packageName;
            this.uid = uid;
            this.stream = stream;
            this.usage = usage;
            this.content = content;
            this.proxy = proxy;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public boolean shouldDuck(boolean applicationNavigation) {
            return NavigationMediaDuckingState.shouldDuck(
                    packageName, uid, stream, usage, content, applicationNavigation);
        }

        @Override
        public void duck() throws Exception {
            applyShape.invoke(proxy, shape, play);
        }

        @Override
        public void restore() throws Exception {
            applyShape.invoke(proxy, shapeId, VolumeShaper.Operation.REVERSE);
        }
    }

    private void report(Exception failure) {
        if (reportedFailure) return;
        reportedFailure = true;
        Log.w(TAG, "Navigation media gain unavailable", failure);
    }

    @Override
    public void close() {
        worker.post(
                () -> {
                    closed = true;
                    worker.removeCallbacks(refresh);
                    state.close();
                    if (watching) audio.unregisterAudioPlaybackCallback(callback);
                    watching = false;
                    thread.quitSafely();
                });
    }
}
