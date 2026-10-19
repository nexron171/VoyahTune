package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * Sends volume keys through the system input pipeline so the OEM volume policy handles them
 * exactly like the hardware buttons.
 */
final class VolumeKeyInjector {
    private static final String TAG = "$$$ VolumeKeyInjector $$$";

    private VolumeKeyInjector() {}

    static void adjust(Context context, int direction) {
        int keyCode =
                direction > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        try {
            Object inputManager = context.getSystemService(Context.INPUT_SERVICE);
            if (inputManager == null) {
                return;
            }
            Method inject =
                    inputManager
                            .getClass()
                            .getMethod("injectInputEvent", InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            inject.invoke(
                    inputManager,
                    new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
                    0);
            inject.invoke(
                    inputManager,
                    new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0),
                    0);
            Log.i(TAG, "volume key injected: " + keyCode);
        } catch (Exception error) {
            Log.w(TAG, "volume key injection failed: " + error.getMessage());
        }
    }
}
