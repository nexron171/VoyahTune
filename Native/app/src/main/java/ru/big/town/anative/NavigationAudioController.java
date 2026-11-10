package ru.big.town.anative;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.NavigationAudioSettings;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NavigationAudioController implements AutoCloseable {
    private static final String TAG = "NavigationAudio";
    private final Context context;
    private final AudioManager audio;
    private final HandlerThread thread = new HandlerThread(TAG);
    private final Handler worker;
    private final NavigationAudioDucking ducking;
    private final NavigationMediaDucking mediaDucking;
    private boolean mediaNavigationActive;
    private final Set<Integer> selectedUids = new HashSet<>();
    private String selectedPackages = "";
    private List<AudioPlaybackConfiguration> playback = Collections.emptyList();
    private Method active;
    private Method clientUid;
    private Method tags;
    private Method streamActive;
    private boolean watchingPlayback;
    private boolean closed;
    private boolean reportedFailure;
    private final Runnable poll = this::poll;

    private final AudioManager.AudioPlaybackCallback callback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configurations) {
                    if (closed) return;
                    playback = configurations;
                    poll();
                }
            };

    private final ContentObserver observer;

    NavigationAudioController(Context context, NavigationMediaDucking mediaDucking) {
        this.mediaDucking = mediaDucking;
        this.context = context.getApplicationContext();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        ducking = new NavigationAudioDucking(new NavigationOemAudioFocus(this.context));
        thread.start();
        worker = new Handler(thread.getLooper());
        observer =
                new ContentObserver(worker) {
                    @Override
                    public void onChange(boolean selfChange) {
                        reload();
                    }
                };
        try {
            context.getContentResolver()
                    .registerContentObserver(
                            Settings.Global.getUriFor(NavigationAudioSettings.GLOBAL_PACKAGES),
                            false,
                            observer);
        } catch (RuntimeException failure) {
            thread.quitSafely();
            throw failure;
        }
        worker.post(this::reload);
    }

    private void reload() {
        if (closed) return;
        selectedPackages =
                NavigationAudioSettings.normalizeCsv(
                        Settings.Global.getString(
                                context.getContentResolver(),
                                NavigationAudioSettings.GLOBAL_PACKAGES));
        selectedUids.clear();
        for (String packageName : selectedPackages.split(",")) {
            if (packageName.isEmpty()) continue;
            try {
                int uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
                if (uid >= 10000) selectedUids.add(uid);
            } catch (android.content.pm.PackageManager.NameNotFoundException removed) {
                Log.d(TAG, "Selected application is not installed: " + packageName);
            }
        }
        try {
            if (!selectedPackages.isEmpty() && !watchingPlayback) {
                audio.registerAudioPlaybackCallback(callback, worker);
                watchingPlayback = true;
            } else if (selectedPackages.isEmpty() && watchingPlayback) {
                audio.unregisterAudioPlaybackCallback(callback);
                watchingPlayback = false;
            }
            playback =
                    selectedPackages.isEmpty()
                            ? Collections.emptyList()
                            : audio.getActivePlaybackConfigurations();
        } catch (RuntimeException failure) {
            report(failure);
        }
        poll();
    }

    private void resolvePlaybackApi() throws Exception {
        if (streamActive != null) return;
        active = AudioPlaybackConfiguration.class.getMethod("isActive");
        clientUid = AudioPlaybackConfiguration.class.getMethod("getClientUid");
        tags = AudioAttributes.class.getMethod("getTags");
        streamActive =
                Class.forName("android.media.AudioSystem")
                        .getMethod("isStreamActive", int.class, int.class);
    }

    private boolean selectedPlayback() throws Exception {
        for (AudioPlaybackConfiguration configuration : playback) {
            AudioAttributes attributes = configuration.getAudioAttributes();
            if (NavigationAudioPlayback.matches(
                    Boolean.TRUE.equals(active.invoke(configuration)),
                    attributes.getVolumeControlStream(),
                    (Integer) clientUid.invoke(configuration),
                    selectedUids,
                    selectedPackages,
                    (Set<?>) tags.invoke(attributes))) return true;
        }
        return false;
    }

    private void poll() {
        worker.removeCallbacks(poll);
        if (closed) return;
        boolean playing = false;
        try {
            if (!selectedPackages.isEmpty()) {
                resolvePlaybackApi();
                // SoundPool leaves PlayerBase started after its final sample; consult the mixer.
                playing =
                        selectedPlayback()
                                && Boolean.TRUE.equals(
                                        streamActive.invoke(
                                                null, NavigationAudioSettings.STREAM, 0));
            }
        } catch (Exception failure) {
            report(failure);
        }
        try {
            ducking.update(playing, SystemClock.elapsedRealtime());
        } catch (Exception failure) {
            report(failure);
        }
        setMediaNavigationActive(ducking.hasFocusRequest());
        if (!selectedPackages.isEmpty() || ducking.hasFocusRequest()) {
            worker.postDelayed(poll, 100);
        }
    }

    private void setMediaNavigationActive(boolean active) {
        if (mediaNavigationActive == active) return;
        mediaNavigationActive = active;
        mediaDucking.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, active);
    }

    private void report(Exception failure) {
        if (reportedFailure) return;
        reportedFailure = true;
        Log.w(TAG, "Application navigation audio unavailable", failure);
    }

    @Override
    public void close() {
        worker.post(
                () -> {
                    closed = true;
                    worker.removeCallbacks(poll);
                    context.getContentResolver().unregisterContentObserver(observer);
                    if (watchingPlayback) audio.unregisterAudioPlaybackCallback(callback);
                    try {
                        ducking.close();
                    } catch (Exception failure) {
                        Log.w(TAG, "Cannot release application navigation focus", failure);
                    }
                    setMediaNavigationActive(false);
                    thread.quitSafely();
                });
    }
}
