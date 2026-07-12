package ru.big.town.anative;

import static ru.big.town.anative.SetModesService.MSG_APPLY_DRIVE_MODES_STAR_BUTTON;
import static ru.big.town.anative.SetModesService.STATE_SHUTDOWN_PREPARE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SetModesReceiverDynamic extends BroadcastReceiver {
    public static volatile int repeat = 7;
    public static volatile boolean isButton = false;
    static final String TAG = "$$$ SetModesReceiverDynamic $$$";

    @Override
    public void onReceive(Context context, Intent intent) {
        String receivedIntent = intent.getAction();

        Log.i(TAG, "onReceive DYN enter by intent" + receivedIntent);

        if ( "android.intent.action.KEYCODE_SWC_USER_DEFINE".equals(receivedIntent)) {
            Log.i(TAG, "android.intent.action.KEYCODE_SWC_USER_DEFINE");
            Log.i(TAG, "GlobalVars.buttonDriveMode: " +
                    GlobalVars.buttonDriveMode);
            //MainActivity.setCanValues(1, MainActivity.getCustomCommandOff());
            switch (GlobalVars.buttonDriveMode){
                case 1:
                    SetModesService.worker(1, 200, MSG_APPLY_DRIVE_MODES_STAR_BUTTON,1);
                    GlobalVars.buttonDriveMode=2;
                    break;
                case 2:
                    SetModesService.worker(1, 200, MSG_APPLY_DRIVE_MODES_STAR_BUTTON,2);
                    GlobalVars.buttonDriveMode=1;
                    break;

            }

        }
//        if (receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
//            repeat = 3;
//            isButton = true;
//        } else {
//            repeat = 7;
//            isButton = false;
//        }
        // Fallback-триггер пробуждения через броадкасты. Держим его активным всегда (даже если
        // power-listener работает): при рестарте CarService слушатель может «протухнуть», а этот
        // путь остаётся. Возможные дубли с power-listener гасит дебаунс в ApplyEngine.
        if (Intent.ACTION_SCREEN_ON.equals(receivedIntent) ||
                "com.android.server.jobscheduler.GARAGE_MODE_OFF".equals(receivedIntent)) {
            Log.i(TAG, "onReceive ACTION_SCREEN_ON or GARAGE_MODE_OFF");
            ApplyEngine.scheduleApply(receivedIntent);
        }

                //throw new UnsupportedOperationException("Not yet implemented");
        if (isOrderedBroadcast()) {
            setResultCode(-1);
        }
    }

}