package ru.big.town.anative;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Launch on the requested display. OEM Android 11 cannot reliably reparent an existing task
 * into/out of a virtual display: retire the old task, keep its process/services, then launch.
 * Binder work is serialized off main; repeated taps cancel stale launches for the same package.
 */
final class AppDisplayLauncher {
    private static final String TAG = "VoyahAppDisplay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> GENERATIONS = new HashMap<>();
    private static final Handler WORKER;
    static {
        HandlerThread thread = new HandlerThread("voyah-display-launch");
        thread.start();
        WORKER = new Handler(thread.getLooper());
    }

    private AppDisplayLauncher() {}

    static synchronized void cancel(String pkg) {
        GENERATIONS.put(pkg, GENERATIONS.getOrDefault(pkg, 0L) + 1);
    }

    private static synchronized long next(String pkg) {
        cancel(pkg);
        return GENERATIONS.get(pkg);
    }

    private static synchronized boolean current(String pkg, long generation) {
        return GENERATIONS.getOrDefault(pkg, 0L) == generation;
    }

    static void launch(Context context, String pkg, int displayId, boolean fullscreen,
                       BooleanSupplier valid, Runnable onFailure) {
        Context app = context.getApplicationContext();
        long generation = next(pkg);
        WORKER.post(() -> prepare(app, pkg, displayId, fullscreen, generation, valid, onFailure));
    }

    private static void prepare(Context app, String pkg, int displayId, boolean fullscreen,
                                long generation, BooleanSupplier valid, Runnable onFailure) {
        if (!current(pkg, generation)) return;
        try {
            Intent intent = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) throw new IllegalStateException("No launcher for " + pkg);
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            if (tasks == null) throw new IllegalStateException("Task list unavailable");
            List<SplitHostTaskSnapshot.TaskRecord> snapshot = new ArrayList<>();
            for (ActivityManager.RunningTaskInfo task : tasks) {
                ComponentName component = task.baseActivity != null ? task.baseActivity : task.topActivity;
                if (component == null || !pkg.equals(component.getPackageName())) continue;
                int oldDisplay = task.getClass().getField("displayId").getInt(task);
                snapshot.add(new SplitHostTaskSnapshot.TaskRecord(task.taskId, pkg, oldDisplay));
            }
            Set<Integer> retiring = AppDisplayTaskPlan.retiring(snapshot, pkg, displayId);
            // Задача уходит с виртуального дисплея (это дисплей embedded-виджета главного экрана):
            // просим хост снять виджет, иначе тот останется с мёртвым Surface — чёрным квадратом.
            for (SplitHostTaskSnapshot.TaskRecord task : snapshot) {
                if (retiring.contains(task.taskId) && task.displayId != null && task.displayId > 1) {
                    SetModesService.notifyEmbeddedTaskLeft(app, pkg);
                    break;
                }
            }
            Object service = null;
            Method remove = null;
            for (int taskId : retiring) {
                if (!current(pkg, generation)) return;
                if (service == null) {
                    service = Class.forName("android.app.ActivityTaskManager")
                            .getMethod("getService").invoke(null);
                    remove = service.getClass().getMethod("removeTask", int.class);
                }
                Object removed = remove.invoke(service, taskId);
                if (Boolean.FALSE.equals(removed)) throw new IllegalStateException("Cannot retire task " + taskId);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            awaitRemoval(app, am, pkg, displayId, fullscreen, generation, intent,
                    retiring, 0, valid, onFailure);
        } catch (Exception e) {
            failed(pkg, generation, valid, onFailure, e);
        }
    }

    private static void awaitRemoval(Context app, ActivityManager am, String pkg, int displayId,
                                     boolean fullscreen, long generation, Intent intent,
                                     Set<Integer> retiring, int attempt,
                                     BooleanSupplier valid, Runnable onFailure) {
        if (!current(pkg, generation)) return;
        try {
            if (!retiring.isEmpty()) {
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
                if (tasks == null) throw new IllegalStateException("Task list unavailable");
                boolean remains = false;
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    if (retiring.contains(task.taskId)) { remains = true; break; }
                }
                if (remains) {
                    if (attempt >= 20) throw new IllegalStateException("Task teardown timed out");
                    WORKER.postDelayed(() -> awaitRemoval(app, am, pkg, displayId, fullscreen,
                            generation, intent, retiring, attempt + 1, valid, onFailure), 100);
                    return;
                }
            }
            MAIN.post(() -> {
                if (!current(pkg, generation) || !valid.getAsBoolean()) return;
                try {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(displayId);
                    Bundle bundle = options.toBundle();
                    if (fullscreen) bundle.putInt("android.activity.windowingMode", 1);
                    DockLaunchGuard.arm(app, displayId, pkg);
                    app.startActivity(intent, bundle);
                    Log.i(TAG, "launched pkg=" + pkg + " display=" + displayId);
                } catch (Exception e) { failed(pkg, generation, valid, onFailure, e); }
            });
        } catch (Exception e) { failed(pkg, generation, valid, onFailure, e); }
    }

    private static void failed(String pkg, long generation, BooleanSupplier valid,
                               Runnable onFailure, Exception error) {
        Log.w(TAG, "launch failed: " + pkg, error);
        MAIN.post(() -> {
            if (current(pkg, generation) && valid.getAsBoolean() && onFailure != null) onFailure.run();
        });
    }
}
