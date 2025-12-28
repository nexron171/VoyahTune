package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.car.hardware.power.CarPowerManager;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class SetModesService extends Service {

    private Messenger clientMessenger;
    static final int MSG_ALLPY_DRIVE_MODES = 1;
    static final int MSG_RESULT = 4;

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ALLPY_DRIVE_MODES:
                    clientMessenger = msg.replyTo;
                    SetModesReceiver.worker(7, 250);
                    Log.i(TAG, "handleMessage() MSG_ALLPY_DRIVE_MODES");
                    try {
                        clientMessenger.send(Message.obtain(null, MSG_RESULT));
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }

                    break;
                default:
                    Log.i(TAG, "handleMessage() default" );
                    super.handleMessage(msg);
            }
        }
    }
    private boolean isWorking = false;
    private static final String TAG = "$$$ SetModesService $$$";
    private SetModesReceiver setModesReceiver;
    private final String CHANNEL_ID = "screen_monitor_channel";
    private Car mCar;
    private CarPowerManager mCarPowerManager;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        initializeCarPowerManager();
        setModesReceiver = new SetModesReceiver();
        Log.i(TAG, "onCreateEd");
    }

    private final CarPowerManager.CarPowerStateListener mPowerStateListener =
            new CarPowerManager.CarPowerStateListener() {
                @Override
                public void onStateChanged(int state) {
                    Log.i(TAG, "Power state changed: " + state);
//                    if(state==2 || state==6 || state==8 || state==10 ){
                    if (state == 6) {
                        //sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
                        //LocalBroadcastManager.getInstance(SetModesService.this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
                        SetModesReceiver.worker(7);
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
            if (!mCar.isConnected()) mCar.connect();

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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action="";
        if(intent != null && intent.getAction()!=null) action=intent.getAction();

        Log.i(TAG, "onStartCommand() Intent: "+ action);
        if (!isWorking) {
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
//            filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES");
//            filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER");

            // Register receiver with filter
            registerReceiver(setModesReceiver, filter, RECEIVER_EXPORTED);
            //sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
            //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
            SetModesReceiver.worker(7);
            //return START_STICKY;
            //return super.onStartCommand( intent,  flags,  startId);

            // Первый вызов после старта сервиса
            Log.i(TAG, "onStartCommand() first run!");
            isWorking = true;
        }
        //if(action.equals("ru.big.town.anative.APPLY_DRIVE_MODES")){
          //  Log.i(TAG, "onStartCommand() Intent is ru.big.town.anative.APPLY_DRIVE_MODES!");
            //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES"));
        //}
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

    final Messenger serviceMessenger = new Messenger(new IncomingHandler());

    @Override
    public IBinder onBind(Intent intent) {
        return serviceMessenger.getBinder();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
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