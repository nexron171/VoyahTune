package ru.big.town.anative;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Restores the last player which had an active media session. The saved package survives reboots;
 * launch is followed by a transport play request and a best-effort move to background.
 */
final class MediaPlayerAutostart {
    private static final String TAG = "$$$ MediaPlayerAutostart $$$";
    private static final String PREFERENCES = "media_autostart";
    private static final String KEY_LAST_PLAYER = "last_player";
    private static final String KEY_LAST_PLAYER_PLAYING = "last_player_playing";
    private static final long PLAYBACK_CHECK_DELAY_MS = 1_000L;
    private static final long PLAYBACK_RETRY_MS = 800L;
    private static final int PLAYBACK_ATTEMPTS = 6;
    private static final long BACKGROUND_CHECK_DELAY_MS = 2_400L;
    private static final int BACKGROUND_CHECK_ATTEMPTS = 3;

    private MediaPlayerAutostart() {}

    static void rememberPlaying(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return;
        }
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        boolean samePlayer = packageName.equals(preferences.getString(KEY_LAST_PLAYER, ""));
        if (samePlayer && preferences.getBoolean(KEY_LAST_PLAYER_PLAYING, false)) {
            return;
        }
        preferences
                .edit()
                .putString(KEY_LAST_PLAYER, packageName)
                .putBoolean(KEY_LAST_PLAYER_PLAYING, true)
                .commit();
        Log.i(TAG, "last player playing: " + packageName);
    }

    /** A user pause clears the resume flag; sleep-induced pauses are ignored by the caller. */
    static void rememberPaused(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return;
        }
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!packageName.equals(preferences.getString(KEY_LAST_PLAYER, ""))) {
            return;
        }
        if (!preferences.getBoolean(KEY_LAST_PLAYER_PLAYING, false)) {
            return;
        }
        preferences.edit().putBoolean(KEY_LAST_PLAYER_PLAYING, false).commit();
        Log.i(TAG, "last player paused: " + packageName);
    }

    static String lastPlayer(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_LAST_PLAYER, "");
    }

    private static boolean lastPlayerWasPlaying(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_LAST_PLAYER_PLAYING, false);
    }

    static boolean launchLastPlayer(Context context, Handler handler) {
        String packageName = lastPlayer(context);
        if (packageName.isEmpty()) {
            Log.i(TAG, "no saved player");
            return false;
        }
        if (!lastPlayerWasPlaying(context)) {
            Log.i(TAG, "last player was paused — autostart skipped");
            return false;
        }
        return launchPlayer(context, packageName, 0, handler);
    }

    /** Manual widget selection: remember the player, open it and ask it to play. */
    static boolean launchPlayerWithPlayback(
            Context context, String packageName, int displayId, Handler handler) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        rememberPlaying(context, packageName);
        return launchPlayer(context, packageName, displayId, handler);
    }

    private static boolean launchPlayer(
            Context context, String packageName, int displayId, Handler handler) {
        int targetDisplay = displayId == 1 ? 1 : 0;
        if (packageName.equals(context.getPackageName())) {
            return false;
        }
        if (isPackagePlaying(context, packageName)) {
            Log.i(TAG, "player already playing: " + packageName);
            return false;
        }
        if (isPackageTop(context, packageName, targetDisplay)) {
            Log.i(TAG, "player already foreground: " + packageName);
            schedulePlayback(context, packageName, handler);
            return false;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Log.w(TAG, "no launch intent for " + packageName);
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(targetDisplay);
        try {
            context.startActivity(launch, options.toBundle());
        } catch (RuntimeException error) {
            Log.w(TAG, "launch failed: " + error.getMessage());
            return false;
        }
        Log.i(TAG, "launched player: " + packageName);
        schedulePlayback(context, packageName, handler);
        if (handler != null) {
            handler.postDelayed(
                    () ->
                            moveToBackgroundIfTop(
                                    context,
                                    packageName,
                                    targetDisplay,
                                    BACKGROUND_CHECK_ATTEMPTS),
                    BACKGROUND_CHECK_DELAY_MS);
        }
        return true;
    }

    private static void schedulePlayback(
            Context context, String packageName, Handler handler) {
        if (handler == null) {
            requestPlayback(context, packageName, null, PLAYBACK_ATTEMPTS);
            return;
        }
        handler.postDelayed(
                () ->
                        requestPlayback(
                                context,
                                packageName,
                                handler,
                                PLAYBACK_ATTEMPTS),
                PLAYBACK_CHECK_DELAY_MS);
    }

    private static boolean requestPlayback(
            Context context, String packageName, Handler handler, int attemptsLeft) {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager)
                            context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager != null) {
                List<MediaController> controllers = manager.getActiveSessions(null);
                for (MediaController controller : controllers) {
                    if (!packageName.equals(controller.getPackageName())) {
                        continue;
                    }
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && MediaControlRouter.isActiveState(state)) {
                        Log.i(TAG, "player already playing: " + packageName);
                        return true;
                    }
                    MediaController.TransportControls controls =
                            controller.getTransportControls();
                    if (controls != null) {
                        controls.play();
                        Log.i(TAG, "play requested: " + packageName);
                        return true;
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "request playback: " + error.getMessage());
        }
        if (attemptsLeft > 1 && handler != null) {
            handler.postDelayed(
                    () ->
                            requestPlayback(
                                    context,
                                    packageName,
                                    handler,
                                    attemptsLeft - 1),
                    PLAYBACK_RETRY_MS);
            return false;
        }
        dispatchPlayKey(context);
        return false;
    }

    private static void dispatchPlayKey(Context context) {
        try {
            AudioManager audioManager =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(
                            now,
                            now,
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            0));
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(
                            now,
                            now,
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            0));
            Log.i(TAG, "media play key dispatched");
        } catch (RuntimeException error) {
            Log.w(TAG, "media play key failed: " + error.getMessage());
        }
    }

    private static boolean isPackagePlaying(Context context, String packageName) {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager)
                            context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return false;
            }
            List<MediaController> controllers = manager.getActiveSessions(null);
            for (MediaController controller : controllers) {
                if (!packageName.equals(controller.getPackageName())) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                return state != null && MediaControlRouter.isActiveState(state);
            }
        } catch (Exception error) {
            Log.w(TAG, "check playback: " + error.getMessage());
        }
        return false;
    }

    private static boolean isPackageTop(
            Context context, String packageName, int displayId) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(50);
            if (tasks == null || tasks.isEmpty()) {
                return false;
            }
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (displayIdOf(task) != displayId) {
                    continue;
                }
                ComponentName component =
                        task.topActivity != null ? task.topActivity : task.baseActivity;
                return component != null && packageName.equals(component.getPackageName());
            }
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static int displayIdOf(ActivityManager.RunningTaskInfo task) {
        try {
            return task.getClass().getField("displayId").getInt(task);
        } catch (Exception error) {
            return 0;
        }
    }

    /**
     * The launch is only useful if the player process starts and creates its media session. Moving
     * the task back keeps the dashboard usable; if the user already switched elsewhere, leave it.
     */
    private static void moveToBackgroundIfTop(
            Context context, String packageName, int displayId, int attemptsLeft) {
        boolean top = false;
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(50);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (displayIdOf(task) != displayId) {
                    continue;
                }
                ComponentName component =
                        task.topActivity != null ? task.topActivity : task.baseActivity;
                top = component != null && packageName.equals(component.getPackageName());
                if (top) {
                    Object service =
                            Class.forName("android.app.ActivityTaskManager")
                                    .getMethod("getService")
                                    .invoke(null);
                    service.getClass()
                            .getMethod("moveTaskToBack", int.class, boolean.class)
                            .invoke(service, task.id, true);
                    Log.i(TAG, "player moved to background: " + packageName);
                }
                break;
            }
        } catch (Exception error) {
            Log.w(TAG, "move to background failed: " + error.getMessage());
        }
        if (!top && attemptsLeft > 1) {
            new Handler(context.getMainLooper())
                    .postDelayed(
                            () ->
                                    moveToBackgroundIfTop(
                                            context,
                                            packageName,
                                            displayId,
                                            attemptsLeft - 1),
                            BACKGROUND_CHECK_DELAY_MS);
        }
    }
}
