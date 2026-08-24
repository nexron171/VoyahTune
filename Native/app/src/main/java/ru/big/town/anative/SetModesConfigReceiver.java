package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

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
            boolean needsBackService = false;
            for (String button : buttons) {
                String shortKey = "steer" + button + "Short";
                String longKey = "steer" + button + "Long";
                SetModesReceiverDynamic.mirrorSteer(context, intent, shortKey);
                SetModesReceiverDynamic.mirrorSteer(context, intent, longKey);
                needsBackService |= "system_back".equals(intent.getStringExtra(shortKey));
                needsBackService |= "system_back".equals(intent.getStringExtra(longKey));
            }
            BackButtonService.setSteeringBackEnabled(context, needsBackService);
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
        } else if ("ru.big.town.anative.APP_DPI_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorAppDpi(context, intent);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "APP_DPI_CONFIG зеркалирован + reload");
        } else if ("ru.big.town.anative.KEYBOARD_CONFIG".equals(action)) {
            applyKeyboardMode(context, intent.getStringExtra("keyboardMode"));
        }
    }

    private static void applyKeyboardMode(Context context, String requestedMode) {
        String mode = normalizeKeyboardMode(requestedMode);
        String previous = Settings.Global.getString(
                context.getContentResolver(), "voyahtune_keyboard_mode");
        String normalizedPrevious = normalizeKeyboardMode(previous);
        if (!Settings.Global.putString(
                context.getContentResolver(), "voyahtune_keyboard_mode", mode)) {
            Log.e(TAG, "KEYBOARD_CONFIG: Settings.Global write failed");
            return;
        }
        if (mode.equals(normalizedPrevious)) {
            Log.i(TAG, "KEYBOARD_CONFIG unchanged: " + mode);
            return;
        }
        // Hooks are eternalized inside qgime. An exact process restart is the only safe way to
        // remove or replace them; load.bin injects at most once into the new Android 11 process.
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Method forceStopPackage = ActivityManager.class.getMethod("forceStopPackage", String.class);
            forceStopPackage.invoke(am, "com.qinggan.app.qgime");
            Log.i(TAG, "KEYBOARD_CONFIG=" + mode + "; Qinggan IME restarted");
        } catch (Exception e) {
            Log.e(TAG, "KEYBOARD_CONFIG saved, but Qinggan IME restart failed", e);
        }
    }

    private static String normalizeKeyboardMode(String mode) {
        return "en".equals(mode) || "ru".equals(mode) ? mode : "off";
    }
}
