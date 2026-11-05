package ru.big.town.anative;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/** Read-only session mirror for the OD media SDK; transport commands go to the real player. */
public final class OemMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "VoyahOemMedia";
    private static final Set<String> CLIENTS = Set.of(
            "com.qinggan.instrumentcard", "com.qinggan.app.launcher",
            "com.qinggan.app.islandapp");
    private static volatile OemMediaBrowserService instance;
    private static volatile Snapshot latest = Snapshot.empty();

    private HandlerThread worker;
    private Handler handler;
    private MediaSession session;
    private Snapshot published;

    static void publish(Context context, String packageName, String appLabel, String title, String artist, String album,
            int state, long position, long duration, boolean hasArt, long artRevision) {
        Snapshot snapshot = new Snapshot(packageName, appLabel, title, artist, album, state, position, duration,
                hasArt, artRevision, false);
        latest = restoreSource(context, snapshot);
        OemMediaBrowserService service = instance;
        if (service != null) {
            service.handler.removeCallbacks(service.refresh);
            service.handler.post(service.refresh);
        }
    }

    static Snapshot snapshot() {
        return latest;
    }

    private static Snapshot restoreSource(Context context, Snapshot snapshot) {
        String saved = MediaPlayerAutostart.lastPlayer(context);
        if (!Snapshot.isExternalPlayer(saved)) {
            return snapshot;
        }
        try {
            PackageManager packages = context.getPackageManager();
            if (packages.getLaunchIntentForPackage(saved) == null) {
                return snapshot;
            }
            String label = packages.getApplicationLabel(packages.getApplicationInfo(saved, 0)).toString();
            return snapshot.restoreSource(saved, label);
        } catch (PackageManager.NameNotFoundException absent) {
            return snapshot;
        }
    }

    private final Runnable refresh = this::refreshSession;

    @Override
    public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("OemMediaMirror");
        worker.start();
        handler = new Handler(worker.getLooper());
        session = new MediaSession(this, "VoyahTune OEM media");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                control(MediaControlPolicy.Command.PLAY_ONLY);
            }

            @Override
            public void onPause() {
                control(MediaControlPolicy.Command.PAUSE_ONLY);
            }

            @Override
            public void onStop() {
                control(MediaControlPolicy.Command.PAUSE_ONLY);
            }

            @Override
            public void onSkipToNext() {
                control(MediaControlPolicy.Command.NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                control(MediaControlPolicy.Command.PREVIOUS);
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if ("GET_PLAT_STATE".equals(action) && trustedController()) {
                    refreshSession();
                }
            }

            @Override
            public void onSeekTo(long position) {
                if (!trustedController()) {
                    return;
                }
                try {
                    MediaController target = currentPlayer();
                    if (target != null && target.getPlaybackState() != null
                            && (target.getPlaybackState().getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                        target.getTransportControls().seekTo(Math.max(0, position));
                    }
                } catch (Exception failure) {
                    Log.w(TAG, "Cannot seek current player", failure);
                }
            }
        }, handler);
        // An active mirror would compete with its source in MediaSessionManager.
        session.setActive(false);
        setSessionToken(session.getSessionToken());
        latest = restoreSource(this, latest);
        instance = this;
        handler.post(refresh);
    }

    // Compatibility is decided by the oem-media hook when it rewires the KW_MUSIC slot.
    static boolean acceptsClient(String clientPackageName) {
        return clientPackageName != null && CLIENTS.contains(clientPackageName);
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (!acceptsClient(clientPackageName)) {
            Log.w(TAG, "Unsupported media browser client: " + clientPackageName);
            return null;
        }
        return new BrowserRoot("now-playing", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<java.util.List<MediaBrowser.MediaItem>> result) {
        result.sendResult(Collections.emptyList());
    }

    private boolean trustedController() {
        return CLIENTS.contains(session.getCurrentControllerInfo().getPackageName());
    }

    private MediaController currentPlayer() {
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        return manager == null ? null
                : MediaControlRouter.controllerForPackage(manager.getActiveSessions(null), latest.packageName);
    }

    private void control(MediaControlPolicy.Command command) {
        try {
            if (!trustedController()) {
                return;
            }
            Snapshot source = latest;
            if (!Snapshot.isExternalPlayer(source.packageName)) {
                return;
            }
            if (source.rememberedSource) {
                if (command == MediaControlPolicy.Command.PLAY_ONLY) {
                    MediaPlayerAutostart.launchPlayerWithPlayback(this, source.packageName, 0, handler);
                }
            } else if (currentPlayer() != null) {
                MediaControlRouter.control(this, command, source.packageName);
            }
        } catch (Exception failure) {
            Log.w(TAG, "Cannot control current player", failure);
        }
    }

    private void refreshSession() {
        Snapshot next = latest;
        try {
            if (!next.sameMetadata(published)) {
                String artwork = next.hasArt
                        ? NowPlayingProvider.ART_URI + "?revision=" + next.artRevision : "";
                MediaMetadata metadata = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,
                                next.packageName + ":" + next.title + ":" + next.artist)
                        .putString(MediaMetadata.METADATA_KEY_TITLE, next.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, next.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, next.album)
                        .putString(MediaMetadata.METADATA_KEY_GENRE, "KW_MUSIC")
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artwork)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artwork)
                        // The OEM KW adapter expects duration in seconds, position in milliseconds.
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, next.duration / 1000)
                        .build();
                session.setMetadata(metadata);
                Intent launch = getPackageManager().getLaunchIntentForPackage(next.packageName);
                session.setSessionActivity(launch == null ? null : PendingIntent.getActivity(
                        this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            MediaController target = next.rememberedSource ? null : currentPlayer();
            PlaybackState playback = target == null ? null : target.getPlaybackState();
            if (next.rememberedSource) {
                actions = PlaybackState.ACTION_PLAY;
            } else if (playback != null) {
                actions = playback.getActions();
            }
            PlaybackState.Builder state = new PlaybackState.Builder().setActions(actions);
            if (playback == null) {
                state.setState(next.state, next.position, next.state == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime());
            } else {
                state.setState(playback.getState(), playback.getPosition(), playback.getPlaybackSpeed(),
                        playback.getLastPositionUpdateTime());
            }
            session.setPlaybackState(state.build());
            published = next;
        } catch (Exception failure) {
            Log.w(TAG, "Cannot publish OEM media snapshot", failure);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            session.release();
            worker.quitSafely();
        });
        super.onDestroy();
    }

    static final class Snapshot {
        final String packageName;
        final String appLabel;
        final boolean rememberedSource;
        final String title;
        final String artist;
        final String album;
        final int state;
        final long position;
        final long duration;
        final boolean hasArt;
        final long artRevision;

        Snapshot(String packageName, String title, String artist, String album, int state,
                long position, long duration, boolean hasArt, long artRevision) {
            this(packageName, packageName, title, artist, album, state, position, duration,
                    hasArt, artRevision, false);
        }

        Snapshot(String packageName, String appLabel, String title, String artist, String album, int state,
                long position, long duration, boolean hasArt, long artRevision, boolean rememberedSource) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.rememberedSource = rememberedSource;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.state = state;
            this.position = position;
            this.duration = duration;
            this.hasArt = hasArt;
            this.artRevision = artRevision;
        }

        static Snapshot empty() {
            return new Snapshot("", "", "", "", 0, 0, 0, false, 0);
        }

        static boolean isExternalPlayer(String packageName) {
            return packageName != null && !packageName.isEmpty() && !packageName.equals("android")
                    && !packageName.startsWith("com.android.") && !packageName.startsWith("com.qinggan.")
                    && !packageName.equals("com.pateo.rdsapp") && !packageName.equals("ru.big.town.anative");
        }

        Snapshot restoreSource(String savedPackage, String savedLabel) {
            if (!isExternalPlayer(savedPackage) || isActivePlayback()
                    || (savedPackage.equals(packageName) && state != PlaybackState.STATE_NONE
                            && state != PlaybackState.STATE_ERROR
                            && (!title.isEmpty() || !artist.isEmpty() || hasArt))) {
                return this;
            }
            return new Snapshot(savedPackage, savedLabel, "", "", "", PlaybackState.STATE_PAUSED,
                    0, 0, false, 0, true);
        }

        private boolean isActivePlayback() {
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_CONNECTING || state == PlaybackState.STATE_FAST_FORWARDING
                    || state == PlaybackState.STATE_REWINDING || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                    || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
        }

        boolean sameMetadata(Snapshot other) {
            return other != null && Objects.equals(packageName, other.packageName)
                    && Objects.equals(title, other.title) && Objects.equals(artist, other.artist)
                    && Objects.equals(album, other.album) && duration == other.duration
                    && hasArt == other.hasArt && (!hasArt || artRevision == other.artRevision);
        }
    }
}
