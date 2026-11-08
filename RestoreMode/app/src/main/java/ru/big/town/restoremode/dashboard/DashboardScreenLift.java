package ru.big.town.restoremode.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;

/** Follows the OEM visible viewport even when WindowManager keeps a 720px window. */
final class DashboardScreenLift implements AutoCloseable {
    static final int RAISED_HEIGHT_PX = 720;
    static final int LOWERED_HEIGHT_PX = 560;
    private static final int DOWN = 1;
    private static final int UP = 2;
    private static final String TAG = "DashboardScreenLift";
    private static final String ACTION_CHANGED = "action.qg.layout.changed";
    private static final String SETTING = "voyahtune_screen_lift_type";
    private static final String PROPERTY = "persist.qg.canbus.bcm_screenAutoLiftFdb";

    private final Context context;
    private final View root;
    private final Runnable changed;
    private boolean lowered;
    private boolean registered;
    private int simulatedLiftType;
    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (intent == null || !ACTION_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    int type = intent.getIntExtra("type", 0);
                    if ((type == DOWN || type == UP) && readProperty(type) == type) {
                        apply(type);
                    }
                }
            };

    DashboardScreenLift(Context context, View root, Runnable changed) {
        this.context = context;
        this.root = root;
        this.changed = changed;
        try {
            ContextCompat.registerReceiver(
                    context,
                    receiver,
                    new IntentFilter(ACTION_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Screen lift receiver unavailable", error);
        }
        refresh();
    }

    void refresh() {
        int type = readProperty(0);
        if (type != DOWN && type != UP) {
            type = Settings.Global.getInt(context.getContentResolver(), SETTING, UP);
        }
        apply(type);
    }

    boolean isLowered() {
        return lowered;
    }

    void toggleSimulation() {
        simulatedLiftType = lowered ? UP : DOWN;
        apply(simulatedLiftType);
    }

    int simulatedLiftType() {
        return simulatedLiftType;
    }

    void restoreSimulation(int type) {
        simulatedLiftType = type == DOWN || type == UP ? type : 0;
        refresh();
    }

    int viewportHeight() {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int displayHeight = RAISED_HEIGHT_PX;
        if (manager != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            // OEM app metrics reserve system UI space even for our fullscreen window.
            manager.getDefaultDisplay().getRealMetrics(metrics);
            if (metrics.heightPixels > 0) {
                displayHeight = metrics.heightPixels;
            }
        }
        return Math.min(displayHeight, lowered ? LOWERED_HEIGHT_PX : RAISED_HEIGHT_PX);
    }

    private void apply(int type) {
        boolean nextLowered = (simulatedLiftType == 0 ? type : simulatedLiftType) == DOWN;
        boolean stateChanged = lowered != nextLowered;
        lowered = nextLowered;
        ViewGroup.LayoutParams parameters = root.getLayoutParams();
        int height = lowered ? viewportHeight() : ViewGroup.LayoutParams.MATCH_PARENT;
        if (parameters.height != height) {
            parameters.height = height;
            root.setLayoutParams(parameters);
        }
        if (stateChanged) {
            changed.run();
        }
    }

    private int readProperty(int fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getInt = properties.getDeclaredMethod("getInt", String.class, int.class);
            int value = (Integer) getInt.invoke(null, PROPERTY, fallback);
            return value == DOWN || value == UP ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    @Override
    public void close() {
        if (registered) {
            context.unregisterReceiver(receiver);
            registered = false;
        }
    }
}
