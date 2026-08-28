package ru.big.town.anative;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the foreground third-party task across the OEM screen-lift transition. The stock
 * ScreenLiftManager may put Launcher on top while it swaps the 720px and 560px shells. We capture
 * task ids before the animation and bring the same tasks back only after WindowManager has applied
 * the new bounds, preserving each application's navigation stack and current activity.
 */
final class ScreenLiftTaskRestorer implements AutoCloseable {
    private static final String TAG = "ScreenLiftRestore";
    private static final String ACTION_START = "action.qg.layout.start_change";
    private static final String ACTION_CHANGED = "action.qg.layout.changed";
    private static final long RESTORE_DELAY_MS = 2_000L;
    private static final String LAUNCHER_PKG = "com.qinggan.app.launcher";
    private static final String SCREEN_LIFT_SETTING = "voyahtune_screen_lift_type";
    private static final String SCREEN_LIFT_PROPERTY = "persist.qg.canbus.bcm_screenAutoLiftFdb";

    private static final String[] STOCK_PREFIXES = {
            "com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei",
            "com.iflytek", "com.iland", "com.mega", "com.qti", "com.qualcomm",
            "com.tencent", "com.nng.igo.primong", "com.bz.CA08"
    };

    private static final class SavedTask {
        final int taskId;
        final int displayId;
        final String packageName;
        final ComponentName component;

        SavedTask(int taskId, int displayId, String packageName, ComponentName component) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.packageName = packageName;
            this.component = component;
        }
    }

    private final Context context;
    private final ActivityManager activityManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, SavedTask> savedByDisplay = new HashMap<>();
    private boolean registered;
    private long generation;
    private Field displayIdField;
    private boolean displayFieldResolved;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_START.equals(action)) {
                capture();
            } else if (ACTION_CHANGED.equals(action)) {
                scheduleRestore(intent.getIntExtra("type", 0));
            }
        }
    };

    ScreenLiftTaskRestorer(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager = (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START);
        filter.addAction(ACTION_CHANGED);
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            registered = true;
            Log.i(TAG, "registered");
        } catch (RuntimeException e) {
            Log.w(TAG, "register failed: " + e.getMessage());
        }
    }

    private void capture() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        savedByDisplay.clear();
        Set<Integer> seenDisplays = new HashSet<>();
        for (ActivityManager.RunningTaskInfo task : runningTasks()) {
            int displayId = displayId(task);
            if ((displayId != 0 && displayId != 1) || !seenDisplays.add(displayId)) continue;
            ComponentName top = task.topActivity;
            if (top == null || !isRestorable(top.getPackageName())) continue;
            savedByDisplay.put(displayId,
                    new SavedTask(task.taskId, displayId, top.getPackageName(), top));
            Log.i(TAG, "captured task=" + task.taskId + " display=" + displayId
                    + " component=" + top.flattenToShortString());
        }
    }

    private void scheduleRestore(int type) {
        if (type != 1 && type != 2) return;
        int actualType = readLiftProperty(type);
        if (actualType != type) {
            Log.w(TAG, "changed broadcast ignored; type=" + type + " property=" + actualType);
            return;
        }
        try {
            Settings.Global.putInt(context.getContentResolver(), SCREEN_LIFT_SETTING, type);
        } catch (RuntimeException e) {
            Log.w(TAG, "persist lift type failed: " + e.getMessage());
        }
        final long expectedGeneration = generation;
        if (savedByDisplay.isEmpty()) return;
        handler.postDelayed(() -> restore(expectedGeneration, type), RESTORE_DELAY_MS);
    }

    /**
     * The OEM actions are exported implicit broadcasts. Verify their mutable extra against the
     * read-only CAN-backed property when hidden-API access is available; a firmware where it is not
     * available keeps the event functional by falling back to the supplied value.
     */
    private int readLiftProperty(int fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getInt = properties.getDeclaredMethod("getInt", String.class, int.class);
            int value = (Integer) getInt.invoke(null, SCREEN_LIFT_PROPERTY, fallback);
            return value == 1 || value == 2 ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    private void restore(long expectedGeneration, int type) {
        if (expectedGeneration != generation) return;
        Map<Integer, SavedTask> pending = new HashMap<>(savedByDisplay);
        savedByDisplay.clear();
        for (SavedTask saved : pending.values()) {
            ComponentName current = topComponent(saved.displayId);
            // The original application survived and is already foreground, or another task won
            // the race by explicit user action. Only Launcher is an expected transient foreground
            // during the OEM shell swap; never steal focus back from any other package.
            if (current != null && saved.packageName.equals(current.getPackageName())) continue;
            if (current != null && !LAUNCHER_PKG.equals(current.getPackageName())) {
                Log.i(TAG, "restore skipped; another app is foreground display=" + saved.displayId
                        + " component=" + current.flattenToShortString());
                continue;
            }
            try {
                activityManager.moveTaskToFront(saved.taskId, 0);
                Log.i(TAG, "restored existing task=" + saved.taskId + " display=" + saved.displayId
                        + " liftType=" + type);
            } catch (RuntimeException moveFailure) {
                launchFallback(saved, moveFailure);
            }
        }
    }

    private void launchFallback(SavedTask saved, RuntimeException moveFailure) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(saved.packageName);
            if (launch == null) throw moveFailure;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(saved.displayId);
            context.startActivity(launch, options.toBundle());
            Log.i(TAG, "restored by launch fallback display=" + saved.displayId
                    + " component=" + saved.component.flattenToShortString());
        } catch (RuntimeException fallbackFailure) {
            Log.w(TAG, "restore failed task=" + saved.taskId + " display=" + saved.displayId
                    + ": " + fallbackFailure.getMessage());
        }
    }

    private ComponentName topComponent(int displayId) {
        ComponentName oemTop = oemTopComponent(displayId);
        if (oemTop != null) return oemTop;
        for (ActivityManager.RunningTaskInfo task : runningTasks()) {
            if (displayId(task) == displayId) return task.topActivity;
        }
        return null;
    }

    /**
     * RunningTaskInfo is ordered inconsistently across physical displays on this firmware: a
     * paused third-party task may precede the actually resumed Launcher task. The OEM service is
     * the same source ScreenLiftManager uses for its per-display foreground decision, so prefer it
     * here and retain the public ActivityManager path as a fail-open fallback for other firmware.
     */
    private ComponentName oemTopComponent(int displayId) {
        try {
            Class<?> serviceManager = Class.forName("com.qinggan.os.ServiceManager");
            Method getTop = serviceManager.getMethod(
                    "getDpyTopAppInfo", Context.class, int.class, int.class);
            Object value = getTop.invoke(null, context, displayId, 4);
            if (!(value instanceof String)) return null;
            String flattened = ((String) value).trim();
            if (flattened.isEmpty()) return null;
            return ComponentName.unflattenFromString(flattened);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "OEM top component unavailable display=" + displayId + ": "
                    + e.getMessage());
            return null;
        }
    }

    private List<ActivityManager.RunningTaskInfo> runningTasks() {
        if (activityManager == null) return java.util.Collections.emptyList();
        try {
            return activityManager.getRunningTasks(64);
        } catch (RuntimeException e) {
            Log.w(TAG, "getRunningTasks failed: " + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private int displayId(ActivityManager.RunningTaskInfo task) {
        if (!displayFieldResolved) {
            displayFieldResolved = true;
            try {
                displayIdField = task.getClass().getField("displayId");
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "RunningTaskInfo.displayId unavailable: " + e.getMessage());
            }
        }
        if (displayIdField == null) return -1;
        try {
            return displayIdField.getInt(task);
        } catch (IllegalAccessException e) {
            return -1;
        }
    }

    private static boolean isRestorable(String pkg) {
        if (pkg == null || pkg.isEmpty() || LAUNCHER_PKG.equals(pkg)) return false;
        if ("com.android.settings".equals(pkg) || "com.android.documentsui".equals(pkg)) return true;
        for (String prefix : STOCK_PREFIXES) {
            if (pkg.startsWith(prefix)) return false;
        }
        return true;
    }

    @Override
    public void close() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        savedByDisplay.clear();
        if (!registered) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        registered = false;
    }
}
