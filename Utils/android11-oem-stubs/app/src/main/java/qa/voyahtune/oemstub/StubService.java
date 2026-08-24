package qa.voyahtune.oemstub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Keeps an otherwise inert Java process alive for injection tests. */
public final class StubService extends Service {
    private static final String CHANNEL_ID = "voyahtune_oem_stub";

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VoyahTune OEM process stubs",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Processes used only by the Android 11 injection test harness");
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getPackageName())
                .setContentText("Android 11 OEM process test stub")
                .setOngoing(true)
                .build();

        int notificationId = 10_000 + (getPackageName().hashCode() & 0x0fff);
        startForeground(notificationId, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
