package ru.big.town.anative;

import static java.lang.Thread.*;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SetModesReceiver extends BroadcastReceiver {
    public static volatile int repeat = 7;
    public static volatile boolean isButton = false;
    public static volatile int running = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        String receivedIntent = intent.getAction();

        Log.i("$$$ SetModesReceiver $$$", "onReceive enter by intent" + receivedIntent);
        if (receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
            repeat = 2;
            isButton = true;
        } else {
            repeat = 7;
            isButton = false;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(receivedIntent)) {
            //context.startService(new Intent(context, SetModesService.class));
            context.startForegroundService(new Intent(context, SetModesService.class));
            Log.i("$$$ SetModesReceiver $$$", "onReceive START SERVICE");
        }

//        final PendingResult result = goAsync();
        MainActivity.initValueModes(context);
        if (running == 0 &&
                (
                        receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER") ||
                        receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES") ||
                        Intent.ACTION_BOOT_COMPLETED.equals(receivedIntent)
                )
        ){
            Thread thread = new Thread() {
                public void run() {
                    try {
                        running = 1;
                        //if(isButton){Thread.sleep(5000);}
                        for (int i = 0; i <= repeat; i++) {
                            Log.i("$$$ SetModesReceiver $$$", receivedIntent + " runCmds();");
                            MainActivity.runCmds();
                            Thread.sleep(3500);
                        }
                        running = 0;
                    } catch (InterruptedException e) {
                        running = 0;
                        throw new RuntimeException(e);
                    }
//                result.setResultCode(0);
//                result.finish();
                }
            };
            thread.start();
        }

        //throw new UnsupportedOperationException("Not yet implemented");
        if (isOrderedBroadcast()) {
            setResultCode(-1);
        }
    }

}