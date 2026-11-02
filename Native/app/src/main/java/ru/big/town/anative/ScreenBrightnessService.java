package ru.big.town.anative;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import ru.big.town.common.BrightnessWidgetProtocol;
import ru.big.town.common.ScreenBrightnessSettings;

import java.util.List;

public final class ScreenBrightnessService extends Service {
    private static final String TAG = "ScreenBrightness";
    private static final String PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final String CHANNEL = "screen_brightness";
    // A stalled vendor Binder must not create a new worker on each service restart.
    static final Handler WORKER;

    static {
        HandlerThread thread = new HandlerThread("ScreenBrightness");
        thread.start();
        WORKER = new Handler(thread.getLooper());
    }

    private final OemScreenBrightnessTransport transport = new OemScreenBrightnessTransport();
    private final ScreenBrightnessController controller = new ScreenBrightnessController();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int latestStartId;
    private SharedPreferences preferences;
    private volatile boolean active;
    private volatile int sensor = -1;
    private volatile List<ScreenBrightnessSettings.Rule> rules;
    private volatile String status = "Подключение к экранам…";
    private final LightSensorService.LevelListener sensorListener =
            level -> {
                if (preferences != null && rulesActive()) {
                    sensor = level;
                    schedule();
                }
            };
    private final BroadcastReceiver requestReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    publishStatus();
                }
            };
    private final Runnable reconcile =
            new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    try {
                        transport.connect(getApplicationContext());
                        int override = currentOverride();
                        boolean enabled = rulesActive();
                        if (enabled
                                && !preferences.getBoolean(
                                        ScreenBrightnessModeController.RESTORE_PENDING, false)) {
                            if (!preferences
                                    .edit()
                                    .putBoolean(
                                            ScreenBrightnessModeController.RESTORE_PENDING, true)
                                    .commit()) {
                                throw new IllegalStateException("Cannot persist mode ownership");
                            }
                        }
                        if (!enabled) {
                            boolean confirmed =
                                    ScreenBrightnessModeController.apply(
                                            transport,
                                            override,
                                            () ->
                                                    active && currentOverride() == override
                                                            && (!preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false)
                                                                || BrightnessWidgetProtocol.paused(override)));
                            if (!confirmed) {
                                throw new IllegalStateException(
                                        "OEM brightness mode not confirmed");
                            }
                            if (preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false)
                                    && !BrightnessWidgetProtocol.paused(override)) return;
                            if (!preferences
                                    .edit()
                                    .putBoolean(
                                            ScreenBrightnessModeController.RESTORE_PENDING, false)
                                    .commit()) {
                                throw new IllegalStateException("Cannot persist mode restoration");
                            }
                            status = BrightnessWidgetProtocol.paused(override)
                                    ? (override == BrightnessWidgetProtocol.DAY ? "День" : "Ночь") + " · правила на паузе"
                                    : "Автоматический режим восстановлен";
                            publishStatus();
                            int completedStart = latestStartId;
                            main.post(
                                    () -> {
                                        if (!preferences.getBoolean(
                                                        ScreenBrightnessSettings.ENABLED, false)
                                                && !preferences.getBoolean(
                                                        ScreenBrightnessModeController
                                                                .RESTORE_PENDING,
                                                        false)) {
                                            stopSelfResult(completedStart);
                                        }
                                    });
                            return;
                        }
                        List<ScreenBrightnessSettings.Rule> snapshot = rules;
                        ScreenBrightnessController.Result result =
                                controller.reconcile(
                                        transport,
                                        snapshot,
                                        sensor,
                                        nightThreshold(),
                                        () ->
                                                active
                                                        && rules == snapshot
                                                        && rulesActive());
                        status =
                                result.target < 0
                                        ? "Основной экран: яркость недоступна"
                                        : "Цель: "
                                                + result.target
                                                + "%"
                                                + (result.night ? " · Ночь" : "")
                                                + " · подтверждено экранов: "
                                                + result.confirmed
                                                + "/3";
                        if (!result.synchronizationConfirmed) {
                            status += " · синхронизация не подтверждена";
                        }
                    } catch (Exception | LinkageError error) {
                        status = "Управление яркостью недоступно · повтор подключения";
                        Log.w(TAG, "OEM brightness unavailable", error);
                    }
                    if (active) {
                        publishStatus();
                        // Readback also repairs missed OEM changes and reconnects after sleep.
                        WORKER.removeCallbacks(this);
                        WORKER.postDelayed(this, 5_000L);
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("NativePrefs", MODE_PRIVATE);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL, "Яркость экрана", NotificationManager.IMPORTANCE_LOW));
        startForeground(
                42,
                new NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Яркость экрана")
                        .setContentText("Управление яркостью дисплеев")
                        .setOngoing(true)
                        .build());
        registerReceiver(
                requestReceiver,
                new IntentFilter(ScreenBrightnessSettings.ACTION_REQUEST),
                PERMISSION,
                null,
                RECEIVER_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent != null && intent.hasExtra(BrightnessWidgetProtocol.MODE)) {
            int mode = intent.getIntExtra(BrightnessWidgetProtocol.MODE, -1);
            if (mode >= 0 && mode <= 2) {
                preferences.edit().putInt(BrightnessWidgetProtocol.OVERRIDE, mode)
                        .putBoolean(ScreenBrightnessModeController.RESTORE_PENDING, true).apply();
            }
        }
        boolean enabled = preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false);
        if (!enabled
                && !preferences.getBoolean(
                        ScreenBrightnessModeController.RESTORE_PENDING, false)) {
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            rules =
                    !rulesActive()
                            ? java.util.Collections.emptyList()
                            : configuredRules();
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "Invalid saved brightness rules", error);
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        active = true;
        if (rulesActive()) {
            LightSensorService.addLevelListener(sensorListener);
            sensor = LightSensorService.lastKnownLevel();
        } else {
            LightSensorService.removeLevelListener(sensorListener);
            sensor = -1;
        }
        if (intent != null && intent.hasExtra("manualPercent")) {
            int percent = intent.getIntExtra("manualPercent", -1);
            int screen = intent.getIntExtra("manualScreen", -1);
            int level = intent.getIntExtra("manualSensor", -1);
            WORKER.post(() -> learnManualBrightness(level, percent, screen));
        }
        schedule();
        return START_STICKY;
    }

    private int nightThreshold() {
        String encoded = preferences.getString(ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.DEFAULT_RULES);
        return preferences.getInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, ScreenBrightnessSettings.nightThreshold(encoded));
    }

    private List<ScreenBrightnessSettings.Rule> configuredRules() {
        String encoded = preferences.getString(ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.DEFAULT_RULES);
        return ScreenBrightnessSettings.levels(encoded);
    }

    private void learnManualBrightness(int level, int percent, int screen) {
        if (!active || !rulesActive() || !preferences.getBoolean(ScreenBrightnessSettings.ADAPTIVE, true)
                || level < 0 || level > 7 || percent < 1 || percent > 100
                || (screen != 1 && screen != 2 && screen != 16)) return;
        try {
            transport.connect(getApplicationContext());
            if (transport.read(screen) != percent) return;
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putInt("sensor", level);
            arguments.putInt("percent", percent);
            android.os.Bundle response = getContentResolver().call(
                    android.net.Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                    ScreenBrightnessSettings.LEARN_METHOD, null, arguments);
            if (response == null || !response.containsKey(ScreenBrightnessSettings.RULES)) return;
            preferences.edit()
                    .putString(ScreenBrightnessSettings.RULES, response.getString(ScreenBrightnessSettings.RULES))
                    .putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, response.getInt(ScreenBrightnessSettings.NIGHT_THRESHOLD))
                    .apply();
            rules = configuredRules();
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "Brightness calibration unavailable", error);
        }
    }

    private boolean rulesActive() {
        return BrightnessWidgetProtocol.rulesActive(
                preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false), currentOverride());
    }

    private int currentOverride() {
        return preferences.getInt(BrightnessWidgetProtocol.OVERRIDE, BrightnessWidgetProtocol.AUTO);
    }

    private void schedule() {
        if (!active) return;
        WORKER.removeCallbacks(reconcile);
        WORKER.post(reconcile);
    }

    private void publishStatus() {
        if (!active) return;
        sendBroadcast(
                new Intent(ScreenBrightnessSettings.ACTION_STATUS)
                        .setPackage("ru.big.town.restoremode")
                        .putExtra("sensorLevel", sensor)
                        .putExtra("status", status),
                PERMISSION);
    }

    @Override
    public void onDestroy() {
        active = false;
        LightSensorService.removeLevelListener(sensorListener);
        WORKER.removeCallbacks(reconcile);
        unregisterReceiver(requestReceiver);
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
