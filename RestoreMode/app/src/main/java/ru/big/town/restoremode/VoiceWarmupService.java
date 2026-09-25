package ru.big.town.restoremode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/** Keeps offline models resident without recording audio or preventing vehicle sleep. */
public final class VoiceWarmupService extends Service {
    private static final String CHANNEL = "voice_ready";
    private static final int NOTIFICATION = 4102;
    private SharedPreferences prefs;
    private boolean requested, destroyed, retaining;
    private int requestedDb = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (store, key) -> {
        if (VoiceCommands.ENABLED.equals(key) || VoiceAudioConfig.DEEP_FILTER_DB_KEY.equals(key)) update();
    };

    /** Called only from visible activities/settings; background process creation does not start FGS. */
    static void sync(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, VoiceWarmupService.class);
        if (!app.getSharedPreferences("DrivePreferences", MODE_PRIVATE).getBoolean(VoiceCommands.ENABLED, false)) {
            app.stopService(intent);
            return;
        }
        try {
            app.startForegroundService(intent);
        } catch (RuntimeException e) {
            // Recognition may still run on demand if an OEM rejects the foreground service.
            Log.w("VoyahVoice", "Cannot start resident voice service", e);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Готовность голосового помощника", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification = notification("Подготовка помощника…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
        prefs.registerOnSharedPreferenceChangeListener(changes);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        update();
        return prefs.getBoolean(VoiceCommands.ENABLED, false) ? START_STICKY : START_NOT_STICKY;
    }

    private void update() {
        if (destroyed) return;
        if (!prefs.getBoolean(VoiceCommands.ENABLED, false)) {
            requested = false;
            releaseModels();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        int db = VoiceAudioConfig.read(prefs).deepFilterDb;
        if (requested && requestedDb == db) return;
        requested = true;
        requestedDb = db;
        retaining = true;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification("Подготовка помощника…"));
        VoiceRecognizer.keepWarm(this, ready -> {
            if (destroyed) return;
            if (!ready) requested = false; // A later visible invocation may retry.
            getSystemService(NotificationManager.class).notify(NOTIFICATION,
                    notification(ready ? "Модели загружены · быстрый запуск" : "Подготовка не удалась. Откройте помощника для повтора"));
        });
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_voice_command)
                .setContentTitle("Голосовой помощник VoyahTune")
                .setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void releaseModels() {
        if (!retaining) return;
        retaining = false;
        VoiceRecognizer.stopKeepingWarm();
    }

    @Override public void onDestroy() {
        destroyed = true;
        prefs.unregisterOnSharedPreferenceChangeListener(changes);
        releaseModels();
        super.onDestroy();
    }
}
