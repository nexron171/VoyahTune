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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
    static final int MSG_APPLY_DRIVE_MODES          = 1;
    static final int MSG_APPLY_DRIVE_MODES_STAR_BUTTON = 2;
    static final int MSG_RESULT                     = 4;
    static final int STATE_ON                       = 6;
    static final int STATE_SHUTDOWN_PREPARE         = 7;
    static final int MSG_AUTO_LIGHT_ENABLE          = 10; // включить автосвет
    static final int MSG_AUTO_LIGHT_DISABLE         = 11; // выключить автосвет
    static final String TAG = "$$$ SetModesService $$$";

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_APPLY_DRIVE_MODES:
                    clientMessenger = msg.replyTo;
                    // MSG_RESULT отправим по ЗАВЕРШЕНИИ цикла применения, чтобы клиент держал
                    // кнопку «Применить» заблокированной всё время отправки.
                    final Messenger replyTo = msg.replyTo;
                    ApplyEngine.applyNow(8, 250, () -> notifyApplyDone(replyTo));
                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES");
                    break;
                case MSG_APPLY_DRIVE_MODES_STAR_BUTTON:
                    clientMessenger = msg.replyTo;
                    worker(1, 100, MSG_APPLY_DRIVE_MODES_STAR_BUTTON, msg.arg1);
                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES_STAR_BUTTON");
                    notifyApplyDone(msg.replyTo);
                    break;

                case MSG_AUTO_LIGHT_ENABLE:
                    Log.i(TAG, "handleMessage() MSG_AUTO_LIGHT_ENABLE");
                    saveAutoLightState(true);
                    startLightSensorService();
                    break;

                case MSG_AUTO_LIGHT_DISABLE:
                    Log.i(TAG, "handleMessage() MSG_AUTO_LIGHT_DISABLE");
                    saveAutoLightState(false);
                    stopLightSensorService();
                    break;

                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    private void saveAutoLightState(boolean enabled) {
        prefs().edit().putBoolean("autoLight", enabled).apply();
        Log.i(TAG, "saveAutoLightState: " + enabled);
    }

    private void restoreAutoLightState() {
        boolean autoLight = prefs().getBoolean("autoLight", false);
        Log.i(TAG, "restoreAutoLightState: autoLight=" + autoLight);
        if (autoLight) {
            startLightSensorService();
        }
    }

    /**
     * На power on: если включена опция «Сервисный режим дворников в холодную погоду»,
     * отправляем {@link WiperColdService} команду reset (вернуть дворники в обычный режим).
     * Сам сервис решит, слать ли toggle (только если считает режим активным).
     */
    private void resetWiperColdOnPowerOn() {
        // Шлём reset, если опция включена ЛИБО персист говорит, что дворники в сервисном
        // режиме (wiperServiceActive). Второе условие важно: если опцию выключили, пока
        // дворники подняты, их всё равно надо вернуть на power on — безусловно, независимо
        // от температуры (решение о самой отправке принимает WiperColdService по флагу).
        boolean enabled = prefs().getBoolean("wiperCold", false);
        boolean active  = prefs().getBoolean("wiperServiceActive", false);
        if (!enabled && !active) return;
        Intent intent = new Intent(this, WiperColdService.class);
        intent.setAction(WiperColdService.ACTION_POWER_ON_RESET);
        startForegroundService(intent);
        Log.i(TAG, "resetWiperColdOnPowerOn: sent POWER_ON_RESET (enabled=" + enabled
                + " active=" + active + ")");
    }

    /** Восстанавливает WiperColdService на старте, если опция была включена. */
    private void restoreWiperColdState() {
        boolean enabled = prefs().getBoolean("wiperCold", false);
        Log.i(TAG, "restoreWiperColdState: wiperCold=" + enabled);
        if (enabled) {
            Intent intent = new Intent(this, WiperColdService.class);
            startForegroundService(intent);
        }
    }

    private void startLightSensorService() {
        Intent intent = new Intent(this, LightSensorService.class);
        startForegroundService(intent);
        Log.i(TAG, "LightSensorService started");
    }

    private void stopLightSensorService() {
        Intent intent = new Intent(this, LightSensorService.class);
        stopService(intent);
        Log.i(TAG, "LightSensorService stopped");
    }

    //private boolean isWorking = false;
    private SetModesReceiverDynamic setModesReceiverDynamic;
    private boolean receiverRegistered = false;
    private final String CHANNEL_ID = "screen_monitor_channel";
    private Car mCar;
    private CarPropertyManager mCarPropertyManager;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        initializeCarPowerManager();
        setModesReceiverDynamic = new SetModesReceiverDynamic();
        Log.i(TAG, "onCreated");
    }



    private final CarPowerManager.CarPowerStateListener mPowerStateListener =
            new CarPowerManager.CarPowerStateListener() {
                @Override
                public void onStateChanged(int state) {
                    Log.i(TAG, "Power state changed: " + state + " (" + powerStateName(state) + ")");
                    // Раньше применялось ТОЛЬКО на STATE_ON(6). Но при выходе из сна железо часто
                    // рапортует WAIT_FOR_VHAL(1)/SUSPEND_EXIT(3)/SHUTDOWN_CANCELLED(8), а ON может не
                    // прийти — из-за этого настройки не применялись до ручного «Применить».
                    // Теперь реагируем на любое «пробуждение к активному состоянию» (с дебаунсом в
                    // ApplyEngine, чтобы несколько состояний подряд не привели к дублю).
                    if (isWakeState(state)) {
                        ApplyEngine.scheduleApply("power state " + powerStateName(state));
                        // Сервисный режим дворников: на пробуждении возвращаем дворники в обычный режим
                        resetWiperColdOnPowerOn();
                    } else {
                        Log.i(TAG, "onStateChanged() ignored state: " + state);
                    }
                }
            };

    /** Состояния питания, трактуемые как «пробуждение → нужно применить настройки». */
    private static boolean isWakeState(int state) {
        return state == CarPowerManager.CarPowerStateListener.ON               // 6
                || state == CarPowerManager.CarPowerStateListener.SUSPEND_EXIT // 3
                || state == CarPowerManager.CarPowerStateListener.WAIT_FOR_VHAL // 1
                || state == CarPowerManager.CarPowerStateListener.SHUTDOWN_CANCELLED; // 8
    }

    private static String powerStateName(int state) {
        switch (state) {
            case CarPowerManager.CarPowerStateListener.WAIT_FOR_VHAL:      return "WAIT_FOR_VHAL";
            case CarPowerManager.CarPowerStateListener.SUSPEND_ENTER:      return "SUSPEND_ENTER";
            case CarPowerManager.CarPowerStateListener.SUSPEND_EXIT:       return "SUSPEND_EXIT";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_ENTER:     return "SHUTDOWN_ENTER";
            case CarPowerManager.CarPowerStateListener.ON:                 return "ON";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_PREPARE:   return "SHUTDOWN_PREPARE";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_CANCELLED: return "SHUTDOWN_CANCELLED";
            default:                                                       return "STATE_" + state;
        }
    }
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
            // Подключаемся к CarService через lifecycle-колбэк: если CarService перезапустится
            // (обычное дело на этом OEM), мы заново получим CarPowerManager и перерегистрируем
            // слушатель питания. Раньше слушатель регистрировался один раз и после рестарта
            // CarService «тихо умирал» — пробуждения переставали ловиться.
            mCar = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                    (car, ready) -> {
                        Log.i(TAG, "Car lifecycle: ready=" + ready);
                        if (ready) {
                            try {
                                GlobalVars.mCarPowerManager =
                                        (CarPowerManager) car.getCarManager(Car.POWER_SERVICE);
                                if (GlobalVars.mCarPowerManager != null) {
                                    registerPowerStateListener();
                                } else {
                                    Log.e(TAG, "Failed to get CarPowerManager");
                                }
                            } catch (Exception e) {
                                GlobalVars.mCarPowerManager = null;
                                Log.e(TAG, "getCarManager(POWER_SERVICE) failed", e);
                            }
                        } else {
                            // CarService отвалился — менеджер невалиден. Отработает fallback
                            // (SCREEN_ON/GARAGE_MODE_OFF), а на реконнекте мы перерегистрируемся.
                            GlobalVars.mCarPowerManager = null;
                        }
                    });
        } catch (Throwable e) {
            GlobalVars.mCarPowerManager = null;
            Log.e(TAG, "Error initializing CarPowerManager", e);
        }
    }

    private void registerPowerStateListener() {
        try {
            // setListener в Android 11 кидает IllegalStateException, если слушатель уже
            // установлен ("Listener must be cleared first") — защищаемся clearListener'ом
            // на случай повторного ready-колбэка без дисконнекта между ними.
            try {
                GlobalVars.mCarPowerManager.clearListener();
            } catch (Throwable ignored) {
                // слушатель не был установлен — это нормально
            }
            GlobalVars.mCarPowerManager.setListener(mPowerStateListener);
            Log.i(TAG, "CarPowerStateListener registered");
        } catch (NoSuchMethodError e) {
            Log.w(TAG, "setListener(Listener) not available on this platform, skipping");
        } catch (Throwable e) {
            Log.e(TAG, "setListener failed: " + e.getMessage());
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

        // Fallback-подписку на пробуждение через броадкасты держим ВСЕГДА (belt-and-suspenders),
        // а не только когда mCarPowerManager==null: слушатель питания может «протухнуть» при
        // рестарте CarService, и тогда единственным триггером остаётся SCREEN_ON/GARAGE_MODE_OFF.
        // Дубли с power-listener гасит дебаунс в ApplyEngine.
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.KEYCODE_SWC_USER_DEFINE");
            filter.addAction("com.android.server.jobscheduler.GARAGE_MODE_OFF");
            filter.addAction("android.intent.action.SCREEN_ON");
            getApplicationContext().registerReceiver(setModesReceiverDynamic, filter, RECEIVER_EXPORTED);
            receiverRegistered = true;
        }

        // Первый вызов после старта сервиса (в т.ч. рестарт по START_STICKY после kill во сне) —
        // применяем настройки. ApplyEngine сам дождётся готовности провайдера/кэша.
        ApplyEngine.scheduleApply("service start");
        Log.i(TAG, "onStartCommand() first run!");

        // Восстанавливаем состояние автосвета
        restoreAutoLightState();
        // Восстанавливаем сервис «сервисного режима дворников»
        restoreWiperColdState();
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
        if (receiverRegistered) {
            try {
                getApplicationContext().unregisterReceiver(setModesReceiverDynamic);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "unregisterReceiver: not registered");
            }
            receiverRegistered = false;
        }
        // Clean up resources
        if (GlobalVars.mCarPowerManager != null) {
            try {
                GlobalVars.mCarPowerManager.clearListener();
                Log.i(TAG, "CarPowerStateListener unregistered");
            } catch (NoSuchMethodError e) {
                Log.w(TAG, "clearListener() not available on this platform");
            } catch (Exception e) {
                Log.w(TAG, "clearListener() failed: " + e.getMessage());
            }
        }

        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }

    /** Уведомить клиента о завершении цикла «Применить» (разблокировка кнопки). */
    static void notifyApplyDone(Messenger client) {
        if (client == null) return;
        try {
            client.send(Message.obtain(null, MSG_RESULT));
        } catch (RemoteException e) {
            Log.w(TAG, "notifyApplyDone failed: " + e.getMessage());
        }
    }
    /**
     * Команда «звёздочки» на руле: разовая отправка пресета 1/2. Выполняется на
     * последовательном потоке {@link ApplyEngine}, чтобы не отправлять в CAN одновременно
     * с циклом применения (раньше взаимное исключение обеспечивал флаг GlobalVars.running,
     * общий с worker'ом применения — сохраняем ту же гарантию, но без сырых потоков).
     */
    static public void worker(int repeat, int pause, int mode, int msg_arg1) {
        Log.i(TAG, " Call worker" +
                String.format(" repeat: %d, pause: %d, mode %d, msg_arg1: %d",
                        repeat, pause, mode, msg_arg1));
        if (GlobalVars.SAVE_CONTEXT == null || mode != MSG_APPLY_DRIVE_MODES_STAR_BUTTON) return;

        ApplyEngine.postExclusive("star button " + msg_arg1, () -> {
            MainActivity.loadModes(GlobalVars.SAVE_CONTEXT);
            Log.i(TAG, " Run customCommandStarButton");
            if (msg_arg1 == 1) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton1(), "star button command 1");
            if (msg_arg1 == 2) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton2(), "star button command 2");
        });
    }
}