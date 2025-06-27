package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

public class SetModesService extends Service {
    private  SetModesReceiver setModesReceiver;
    private final String CHANNEL_ID = "screen_monitor_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        setModesReceiver = new SetModesReceiver();
    }


    public SetModesService() {
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId){
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Monitor")
                .setContentText("Monitoring screen state")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        createNotificationChannel();
        startForeground(1, notification);

        //BroadcastReceiver powerSaveReceiver = new SetModesReceiver();
        IntentFilter filter = new IntentFilter();

        filter.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
        filter.addAction("android.intent.action.SCREEN_ON");
        filter.addAction("com.android.server.jobscheduler.GARAGE_MODE_OFF");
        filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES");

        // Register receiver with filter
        registerReceiver(setModesReceiver, filter, RECEIVER_EXPORTED);

        //return START_STICKY;
        //return super.onStartCommand( intent,  flags,  startId);
        return START_STICKY;
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        //throw new UnsupportedOperationException("Not yet implemented");
        return null;
    }
}