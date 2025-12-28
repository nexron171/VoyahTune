package ru.big.town.anative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SetModesReceiver extends BroadcastReceiver {
    public static volatile int repeat = 7;
    public static volatile boolean isButton = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        String receivedIntent = intent.getAction();

        Log.i("$$$ SetModesReceiver $$$", "onReceive enter by intent" + receivedIntent);
        if (receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
            repeat = 3;
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

        if (Intent.ACTION_BOOT_COMPLETED.equals(receivedIntent)) worker(repeat);

        //throw new UnsupportedOperationException("Not yet implemented");
        if (isOrderedBroadcast()) {
            setResultCode(-1);
        }
    }

    static public void worker(int repeat) {
        Log.i("$$$ SetModesReceiver $$$", " Call worker");
        if (GlobalVars.running == 0 && GlobalVars.SAVE_CONTEXT != null) {
            Log.i("$$$ SetModesReceiver $$$", " Run worker");

            MainActivity.initValueModes(GlobalVars.SAVE_CONTEXT);

            Thread thread = new Thread() {
                public void run() {
                    try {
                        GlobalVars.running = 1;
                        //if(isButton){Thread.sleep(5000);}
                        for (int i = 0; i <= repeat; i++) {
                            MainActivity.runCmds();
                            Thread.sleep(3500);
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
    static public void worker(int repeat, int pause) {
        Log.i("$$$ SetModesReceiver $$$", " Call worker");
        if (GlobalVars.running == 0 && GlobalVars.SAVE_CONTEXT != null) {
            Log.i("$$$ SetModesReceiver $$$", " Run worker");

            MainActivity.initValueModes(GlobalVars.SAVE_CONTEXT);

            Thread thread = new Thread() {
                public void run() {
                    try {
                        GlobalVars.running = 1;
                        //if(isButton){Thread.sleep(5000);}
                        for (int i = 0; i <= repeat; i++) {
                            MainActivity.runCmds();
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