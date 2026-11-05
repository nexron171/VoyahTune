package ru.big.town.anative;

import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ru.big.town.common.ApplicationStartupSettings;
import ru.big.town.common.ApplicationStartupSettings.Screen;

import java.util.ArrayList;
import java.util.List;

final class ApplicationStartupController {
    private static final String TAG = "ApplicationStartup";
    private final SetModesService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApplicationStartupSequence sequence;
    private final ApplicationStartupSequence passengerSequence;
    private volatile boolean closed;

    ApplicationStartupController(SetModesService service) {
        this.service = service;
        sequence =
                new ApplicationStartupSequence(
                        (action, completion) -> launch(action, Screen.MAIN, completion),
                        (action, delay) -> handler.postDelayed(action, delay));
        passengerSequence =
                new ApplicationStartupSequence(
                        (action, completion) -> launch(action, Screen.PASSENGER, completion),
                        (action, delay) -> handler.postDelayed(action, delay));
    }

    void start(Runnable onConfigured, Runnable onPassengerConfigured) {
        new Thread(
                        () -> {
                            readConfiguration(Screen.MAIN, sequence, onConfigured);
                            readConfiguration(
                                    Screen.PASSENGER, passengerSequence, onPassengerConfigured);
                        },
                        "application-startup")
                .start();
    }

    private void readConfiguration(
            Screen screen, ApplicationStartupSequence startupSequence, Runnable onConfigured) {
        List<String> actions = new ArrayList<>();
        long delayMillis;
        try (Cursor cursor =
                service.getContentResolver()
                        .query(Uri.parse(screen.uri()), null, null, null, null)) {
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
                if (action != null
                        && (action.startsWith("app:")
                                || (screen == Screen.MAIN && action.startsWith("split:"))))
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
                    startupSequence.start(actions, delayMillis);
                });
    }

    private void launch(String action, Screen screen, Runnable completion) {
        if (closed) return;
        Log.i(TAG, "Starting " + action);
        if (action.startsWith("split:")) {
            SetModesReceiverDynamic.handleSteerAction(service, action, completion);
            return;
        }
        String packageName = action.substring(4);
        ClusterMediaHostActivity.closeForPackage(packageName);
        if (screen == Screen.MAIN) SplitHostActivity.closeActiveHost();
        boolean fullscreen =
                FullscreenPackagePolicy.contains(
                        android.provider.Settings.Global.getString(
                                service.getContentResolver(), "voyahtune_fullscreen_apps"),
                        packageName);
        AppDisplayLauncher.launch(
                service,
                packageName,
                screen.displayId,
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
        passengerSequence.cancel();
        handler.removeCallbacksAndMessages(null);
    }
}
