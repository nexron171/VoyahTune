package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SetModesReceiverStatic extends BroadcastReceiver {
    static final String TAG = "$$$ SetModesReceiverStatic $$$";


    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"com.qinggan.intent.QINGGAN_BOOT_COMPLETE".equals(action)) {
            Log.w(TAG, "ignored unexpected action: " + action);
            return;
        }
        context.startForegroundService(new Intent(context, SetModesService.class));
        Log.i(TAG, "onReceive boot action: " + action);
    }
}
