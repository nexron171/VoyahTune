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
    public static volatile int running=0;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("$$$ SetModesReceiver $$$", "onReceive enter");
        if (intent.getAction().equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
            repeat = 3;
        } else { repeat = 7; }

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            //context.startService(new Intent(context, SetModesService.class));
            context.startForegroundService(new Intent(context, SetModesService.class));
            Log.i("$$$ SetModesReceiver $$$", "onReceive START SERVICE");
        }

//        final PendingResult result = goAsync();
        MainActivity.initValueModes(context);
        String action = intent.getAction();
        if( running==0) {
            Thread thread = new Thread() {
                public void run() {
                    try {
                        running=1;
                        for (int i = 0; i <= repeat; i++) {
                            Log.i("$$$ SetModesReceiver $$$", action+" runCmds();");
                            MainActivity.runCmds();
                            Thread.sleep(1500);
                        }
                        running=0;
                    } catch (InterruptedException e) {
                        running=0;
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