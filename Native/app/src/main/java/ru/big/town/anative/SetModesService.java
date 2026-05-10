package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
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

import java.util.Arrays;
import java.util.List;


public class SetModesService extends Service {

    private Messenger clientMessenger;
    static final int MSG_APPLY_DRIVE_MODES = 1;
    static final int MSG_APPLY_DRIVE_MODES_STAR_BUTTON = 2;
    static final int MSG_RESULT = 4;
    static final int STATE_ON = 6;
    static final int STATE_SHUTDOWN_PREPARE = 7;
    static final String TAG = "$$$ SetModesService $$$";
        class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_APPLY_DRIVE_MODES:
                    clientMessenger = msg.replyTo;
                    worker(7, 250);
                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES");
                    try {
                        clientMessenger.send(Message.obtain(null, MSG_RESULT));
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case MSG_APPLY_DRIVE_MODES_STAR_BUTTON:
//                    CarPropertyValue<Integer> gear = mCarPropertyManager.getProperty(
//                            VehiclePropertyIds.GEAR_SELECTION, 1);
//                    Log.i(TAG,"###############" + gear.toString());
                    clientMessenger = msg.replyTo;
                    worker(1, 100, MSG_APPLY_DRIVE_MODES_STAR_BUTTON, msg.arg1);

                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES_STAR_BUTTON");
                    try {
                        clientMessenger.send(Message.obtain(null, MSG_RESULT));
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                default:
                    //Log.i(TAG, "handleMessage() default - " + String.format("%d",msg.what));
                    super.handleMessage(msg);
            }
        }
    }

    //private boolean isWorking = false;
    private SetModesReceiverDynamic setModesReceiverDynamic;
    private final String CHANNEL_ID = "screen_monitor_channel";
    private Car mCar;
    private CarPropertyManager mCarPropertyManager;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        initializeCarPowerManager();

        setModesReceiverDynamic = new SetModesReceiverDynamic();
        Log.i(TAG, "onCreateEd");
    }



    private final CarPowerManager.CarPowerStateListener mPowerStateListener =
            new CarPowerManager.CarPowerStateListener() {
                @Override
                public void onStateChanged(int state) {
                    Log.i(TAG, "Power state changed: " + state);
//                    if(state==2 || state==6 || state==8 || state==10 ){
                    if (state == STATE_ON) {
                        //sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
                        //LocalBroadcastManager.getInstance(SetModesService.this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
                        worker(7, 3500);
                    } else {
                        Log.i(TAG, "onStateChanged() else: " + state);
                    }
//                    if (state == STATE_SHUTDOWN_PREPARE) {
//                        Log.i("$$$ SetModesReceiver $$$", "STATE_SHUTDOWN_PREPARE Command is - "
//                                + MainActivity.customCommandStarButton +"EOL");
//                        //MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton());
//                        SetModesReceiver.worker(1, 1, STATE_SHUTDOWN_PREPARE);
//                    }

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
            GlobalVars.mCarPowerManager = (CarPowerManager) mCar.getCarManager(Car.POWER_SERVICE);

            if (GlobalVars.mCarPowerManager != null) {
                // Create executor for listener callbacks
                //mExecutor = Executors.newSingleThreadExecutor();

                // Register the power state listener
                GlobalVars.mCarPowerManager.setListener(mPowerStateListener);
                Log.i(TAG, "CarPowerStateListener registered successfully");
            } else {
                Log.e(TAG, "Failed to get CarPowerManager");
            }
        } catch (Exception e) {
            GlobalVars.mCarPowerManager = null;
            Log.e(TAG, "Error initializing CarPowerManager", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //handler.post(checkScreenState);

        //String action = "";
        //if (intent != null && intent.getAction() != null) action = intent.getAction();

        //Log.i(TAG, "onStartCommand() Intent: " + action);
        //if (!isWorking) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Monitor")
                .setContentText("Monitoring screen state")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        createNotificationChannel();
        startForeground(1, notification);

        //if (GlobalVars.mCarPowerManager == null) {
            //BroadcastReceiver powerSaveReceiver = new SetModesReceiver();
            IntentFilter filter = new IntentFilter();
            //filter.addAction("android.intent.action.SCREEN_ON");
            //filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction("android.intent.action.KEYCODE_SWC_USER_DEFINE");

            if(GlobalVars.mCarPowerManager == null) {
                filter.addAction("com.android.server.jobscheduler.GARAGE_MODE_OFF");
                filter.addAction("android.intent.action.SCREEN_ON");
            }
            //filter.addAction("android.intent.action.BOOT_COMPLETED");
            //filter.addAction("com.qinggan.intent.QINGGAN_BOOT_COMPLETE");
            //filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES");
            //filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER");

            // Register receiver with filter
            //registerReceiver(setModesReceiver, filter, RECEIVER_EXPORTED);
            getApplicationContext().registerReceiver(setModesReceiverDynamic, filter, RECEIVER_EXPORTED);
            //sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
            //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER"));
            worker(7, 3500);
            //return START_STICKY;
            //return super.onStartCommand( intent,  flags,  startId);
        //}
            // Первый вызов после старта сервиса
            Log.i(TAG, "onStartCommand() first run!");
            //   isWorking = true;
        //}
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
        getApplicationContext().unregisterReceiver(setModesReceiverDynamic);
        // Clean up resources
        if (GlobalVars.mCarPowerManager != null) {
            GlobalVars.mCarPowerManager.clearListener();
            Log.i(TAG, "CarPowerStateListener unregistered");
        }

        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }

    static public void worker(int repeat, int pause) {
        Log.i(TAG, " Call worker" +
                String.format("repeat: %d, pause: %d",
                        repeat,pause));

        if (GlobalVars.running == 0 && GlobalVars.SAVE_CONTEXT != null) {
            Log.i(TAG, " Run worker");

            MainActivity.initValueModes(GlobalVars.SAVE_CONTEXT);
            Log.i(TAG, " Command is  1 - " + MainActivity.customCommandStarButton1+" 2 -" + MainActivity.customCommandStarButton2);

            Thread thread = new Thread() {
                public void run() {
                    try {
                        GlobalVars.running = 1;
                        Log.i(TAG, " Start thread");
                        //if(isButton){Thread.sleep(5000);}

                            for (int i = 0; i <= repeat; i++) {
                                MainActivity.runCmds();
                                Thread.sleep(pause);
                            }

                            for (int i = 1; i <= MainActivity.customCommandCount; i++) {
                                MainActivity.setCanValues(1, MainActivity.getCustomCommand());
                                Thread.sleep(pause);
                                Log.i(TAG, " Run customCommand");
                            }

                        GlobalVars.running = 0;
                    } catch (InterruptedException e) {
                        GlobalVars.running = 0;
                        throw new RuntimeException(e);
                    }
//                result.setResultCode(0);
//                result.finish();
                }
            };
            thread.start();
        }
    }
    static public void worker(int repeat, int pause, int mode, int msg_arg1) {
        Log.i(TAG, " Call worker" +
                String.format("repeat: %d, pause: %d, mode %d, msg_arg1: %d",
                        repeat, pause, mode, msg_arg1));

        if (GlobalVars.running == 0 && GlobalVars.SAVE_CONTEXT != null) {
            Log.i(TAG, " Run worker");

            MainActivity.initValueModes(GlobalVars.SAVE_CONTEXT);
            Log.i(TAG, " Command is  1 - " + MainActivity.customCommandStarButton1+" 2 -" + MainActivity.customCommandStarButton2);

            Thread thread = new Thread() {
                public void run() {
                    try {
                        GlobalVars.running = 1;
                        Log.i(TAG, " Start thread");
                        //if(isButton){Thread.sleep(5000);}
                        if (mode == MSG_APPLY_DRIVE_MODES_STAR_BUTTON) {
                            Log.i(TAG, " Run customCommandStarButton");
                            if(msg_arg1==1) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton1());
                            if(msg_arg1==2) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton2());
                            Thread.sleep(pause);
                        }
                        GlobalVars.running = 0;
                    } catch (InterruptedException e) {
                        GlobalVars.running = 0;
                        throw new RuntimeException(e);
                    }
//                result.setResultCode(0);
//                result.finish();
                }
            };
            thread.start();
        }
    }
}