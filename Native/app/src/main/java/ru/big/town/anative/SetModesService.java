package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.car.hardware.power.CarPowerManager;
import android.util.Log;


import androidx.core.app.NotificationCompat;

public class SetModesService extends Service {
    private static final String TAG="$$$ SetModesService $$$";
    private  SetModesReceiver setModesReceiver;
    private final String CHANNEL_ID = "screen_monitor_channel";

    private Car mCar;
    private CarPowerManager mCarPowerManager;

    @Override
    public void onCreate() {
        Log.i(TAG,"onCreate()");
        super.onCreate();
        initializeCarPowerManager();
        setModesReceiver = new SetModesReceiver();
        Log.i(TAG,"onCreateEd");

    }
    private final CarPowerManager.CarPowerStateListener mPowerStateListener =
            new CarPowerManager.CarPowerStateListener() {
                @Override
                public void onStateChanged(int state) {
                    Log.i(TAG, "Power state changed: " + state);
//                    if(state==2 || state==6 || state==8 || state==10 ){
                    if( state == 6 ){
                        sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
                    }

//                    STATE_SHUTDOWN_PREPARE 7
//                    STATE_SUSPEND_ENTER 2
//
//                    STATE_SUSPEND_EXIT 3
//                    STATE_ON 6
                }
            };
//    private void handleSuspendEnter() {
//        Log.i(TAG, "SUSPEND_ENTER received - System is entering suspend-to-RAM");
//
//        // Perform cleanup operations before suspend
//        // Note: You have limited time (default 5 seconds) to complete tasks :cite[3]
//        cleanupBeforeSuspend();
//
//        Log.i(TAG, "Ready for suspend");
//    }
//    private void cleanupBeforeSuspend() {
//        // Add your cleanup logic here:
//        // - Save application state
//        // - Close network connections
//        // - Release resources
//        // - Stop ongoing operations
//
//        try {
//            // Example cleanup operations
//            Log.i(TAG, "Performing pre-suspend cleanup...");
//            Thread.sleep(500); // Simulate cleanup work
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }

    private void initializeCarPowerManager() {
        try {
            // Create Car instance
            mCar = Car.createCar(this);
            if(!mCar.isConnected()) mCar.connect();

            // Get CarPowerManager instance
            mCarPowerManager = (CarPowerManager) mCar.getCarManager(Car.POWER_SERVICE);

            if (mCarPowerManager != null) {
                // Create executor for listener callbacks
                //mExecutor = Executors.newSingleThreadExecutor();

                // Register the power state listener
                mCarPowerManager.setListener(mPowerStateListener);
                Log.i(TAG, "CarPowerStateListener registered successfully");
            } else {
                Log.e(TAG, "Failed to get CarPowerManager");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing CarPowerManager", e);
        }
    }

    public SetModesService() {
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId){
        Log.i(TAG, "onStartCommand()");

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
        filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER");

        // Register receiver with filter
        registerReceiver(setModesReceiver, filter, RECEIVER_EXPORTED);
        sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));

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

    @Override
    public void onDestroy() {
        Log.i(TAG,"onDestroy()");
        super.onDestroy();

        // Clean up resources
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
            Log.i(TAG, "CarPowerStateListener unregistered");
        }

        if (mCar != null) {
            mCar.disconnect();
        }
    }
}