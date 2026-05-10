package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SetModesReceiverStatic extends BroadcastReceiver {
    static final String TAG = "$$$ SetModesReceiverStatic $$$";


    @Override
    public void onReceive(Context context, Intent intent) {
        //String receivedIntent = intent.getAction();

        //if (Intent.ACTION_BOOT_COMPLETED.equals(receivedIntent)) {
            //context.startService(new Intent(context, SetModesService.class));
            context.startForegroundService(new Intent(context, SetModesService.class));
            Log.i(TAG, "onReceive ACTION_BOOT_COMPLETED");
        //}

        //if (Intent.ACTION_BOOT_COMPLETED.equals(receivedIntent)) worker(repeat);
        // TODO: This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast.
        //throw new UnsupportedOperationException("Not yet implemented");
    }
}