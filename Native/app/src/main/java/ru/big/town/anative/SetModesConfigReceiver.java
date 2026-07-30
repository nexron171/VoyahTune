package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Защищённый signature-permission вход для конфигурации из RestoreMode. Launcher/steering hooks
 * используют отдельный публичный SetModesReceiverDynamic, который больше не принимает конфиг.
 */
public class SetModesConfigReceiver extends BroadcastReceiver {
    private static final String TAG = "$$$ SetModesConfig $$$";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.IS_FULL) return;
        String action = intent.getAction();
        if ("ru.big.town.anative.STEER_CONFIG".equals(action)) {
            String[] buttons = {"Star", "Dvr", "Voice", "Phone"};
            for (String button : buttons) {
                SetModesReceiverDynamic.mirrorSteer(context, intent, "steer" + button + "Short");
                SetModesReceiverDynamic.mirrorSteer(context, intent, "steer" + button + "Long");
            }
            Log.i(TAG, "STEER_CONFIG зеркалирован");
        } else if ("ru.big.town.anative.DOCK_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorDock(context, intent, 1);
            SetModesReceiverDynamic.mirrorDock(context, intent, 2);
            Intent reload = new Intent("ru.big.town.anative.DOCK_RELOAD");
            reload.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(reload);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "DOCK_CONFIG зеркалирован + reload");
        } else if ("ru.big.town.anative.FREEFORM_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorFreeform(context, intent);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "FREEFORM_CONFIG зеркалирован + reload");
        }
    }
}
