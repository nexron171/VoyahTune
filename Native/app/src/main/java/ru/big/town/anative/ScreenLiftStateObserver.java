package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;

/** Keeps a verified lift-state fallback for windows that cannot read the OEM property. */
final class ScreenLiftStateObserver implements AutoCloseable {
    private static final String TAG = "ScreenLiftState";
    private static final String ACTION_CHANGED = "action.qg.layout.changed";
    private static final String SCREEN_LIFT_SETTING = "voyahtune_screen_lift_type";
    private static final String SCREEN_LIFT_PROPERTY = "persist.qg.canbus.bcm_screenAutoLiftFdb";

    private final Context context;
    private boolean registered;
    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (intent != null && ACTION_CHANGED.equals(intent.getAction())) {
                        persistState(intent.getIntExtra("type", 0));
                    }
                }
            };

    ScreenLiftStateObserver(Context context) {
        this.context = context.getApplicationContext();
    }

    void register() {
        if (registered) {
            return;
        }
        try {
            ContextCompat.registerReceiver(
                    context,
                    receiver,
                    new IntentFilter(ACTION_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Register failed", error);
        }
    }

    private void persistState(int type) {
        if (type != 1 && type != 2) {
            return;
        }
        int actualType = readLiftProperty(type);
        if (actualType != type) {
            Log.w(TAG, "Changed broadcast ignored; type=" + type + " property=" + actualType);
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), SCREEN_LIFT_SETTING, type);
        } catch (RuntimeException error) {
            Log.w(TAG, "Persist lift type failed", error);
        }
    }

    private int readLiftProperty(int fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getInt = properties.getDeclaredMethod("getInt", String.class, int.class);
            int value = (Integer) getInt.invoke(null, SCREEN_LIFT_PROPERTY, fallback);
            return value == 1 || value == 2 ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return fallback;
        }
    }

    @Override
    public void close() {
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        registered = false;
    }
}
