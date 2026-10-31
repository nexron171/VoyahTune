package ru.big.town.anative;

import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ru.big.town.common.ApplicationStartupSettings;

import java.util.ArrayList;
import java.util.List;

final class ApplicationStartupController {
    private static final String TAG = "ApplicationStartup";
    private final SetModesService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApplicationStartupSequence sequence;
    private volatile boolean closed;

    ApplicationStartupController(SetModesService service) {
        this.service = service;
        sequence =
                new ApplicationStartupSequence(
                        this::launch, (action, delay) -> handler.postDelayed(action, delay));
    }

    void start(Runnable onConfigured) {
        new Thread(() -> readConfiguration(onConfigured), "application-startup").start();
    }

    private void readConfiguration(Runnable onConfigured) {
        List<String> actions = new ArrayList<>();
        long delayMillis;
        try (Cursor cursor =
                service.getContentResolver()
                        .query(Uri.parse(ApplicationStartupSettings.URI), null, null, null, null)) {
            if (cursor == null
                    || !cursor.moveToFirst()
                    || cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 0) return;
            delayMillis =
                    1000L
                            * Math.max(
                                    0,
                                    Math.min(
                                            ApplicationStartupSettings.MAX_DELAY_SECONDS,
                                            cursor.getInt(
                                                    cursor.getColumnIndexOrThrow("delaySeconds"))));
            do {
                String action = cursor.getString(cursor.getColumnIndexOrThrow("action"));
                if (action != null && (action.startsWith("app:") || action.startsWith("split:")))
                    actions.add(action);
            } while (cursor.moveToNext());
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot read startup settings", exception);
            return;
        }
        handler.post(
                () -> {
                    if (closed || actions.isEmpty()) return;
                    onConfigured.run();
                    sequence.start(actions, delayMillis);
                });
    }

    private void launch(String action, Runnable completion) {
        if (closed) return;
        Log.i(TAG, "Starting " + action);
        if (action.startsWith("split:")) {
            SetModesReceiverDynamic.handleSteerAction(service, action, completion);
            return;
        }
        String packageName = action.substring(4);
        ClusterMediaHostActivity.closeForPackage(packageName);
        SplitHostActivity.closeActiveHost();
        boolean fullscreen =
                FullscreenPackagePolicy.contains(
                        android.provider.Settings.Global.getString(
                                service.getContentResolver(), "voyahtune_fullscreen_apps"),
                        packageName);
        AppDisplayLauncher.launch(
                service,
                packageName,
                0,
                fullscreen,
                () -> !closed,
                () -> {
                    Log.w(TAG, "Cannot launch " + packageName);
                    completion.run();
                },
                completion);
    }

    void close() {
        closed = true;
        sequence.cancel();
        handler.removeCallbacksAndMessages(null);
    }
}
