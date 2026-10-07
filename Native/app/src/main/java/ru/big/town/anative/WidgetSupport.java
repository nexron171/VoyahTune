package ru.big.town.anative;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WidgetSupport {
    private static final String TAG = "NativeWidgets";
    // Ключ SharedPreferences для сохранения пакета вручную запущенного приложения.
    // Используется для автозапуска при повторном открытии MainActivity (когда у плитки отключён автозапуск).
    static final String PREF_LAST_MANUAL_APP = "lastManualApp";
    /** CSV зафиксированных приложений: их не закрывает «Закрыть все» (диспетчер и «Дополнительно»). */
    static final String PREF_PINNED_APPS = "pinnedApps";
    private static final String PREF_FILE = "NativePrefs";
    static final String EXTRA_PACKAGE = "package";
    static final String ACTION_OPEN_APP = "ru.big.town.anative.WIDGET_OPEN_APP";
    static final String ACTION_CLOSE_APP = "ru.big.town.anative.WIDGET_CLOSE_APP";
    static final String ACTION_CLOSE_ALL = "ru.big.town.anative.WIDGET_CLOSE_ALL";
    // Обычный запуск приложения (simpleLaunch) — без VirtualDisplay/сплита,
    // просто обычная задача на физическом экране display 0.
    static final String ACTION_SIMPLE_LAUNCH = "ru.big.town.anative.WIDGET_SIMPLE_LAUNCH";
    // Развернуть приложение на весь экран (fullscreen) — через SplitHostActivity single pane.
    static final String ACTION_FULLSCREEN_LAUNCH = "ru.big.town.anative.WIDGET_FULLSCREEN_LAUNCH";
    // Скрытое поле RunningTaskInfo.displayId: разрешается один раз, как в AppDisplayLauncher.
    private static Field displayField;
    private static boolean displayFieldResolved;

    private WidgetSupport() {}

    static String homePackage(Context context) {
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
    }

    static boolean isExternal(Context context, String packageName) {
        if (packageName == null || packageName.equals(context.getPackageName())
                || packageName.equals("ru.big.town.restoremode")
                || packageName.equals(homePackage(context))) return false;
        if (packageName.equals("com.android.contacts") || packageName.equals("com.android.car.dialer")) return true;
        if (packageName.startsWith("com.qinggan") || packageName.startsWith("com.android.car")) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Запущенные сторонние задачи: пакет, подпись, id задачи (нужен диспетчеру для переключения) и
     * признак «приложение живёт внутри виджета». Задача на VirtualDisplay виджета — обычная задача,
     * поэтому она попадает сюда наравне с задачами физических экранов.
     */
    static List<RunningApp> runningApps(Context context, Set<Integer> widgetDisplays) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return new ArrayList<>();
        List<TaskListPolicy.TaskCandidate> candidates = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
            if (component == null || !isExternal(context, component.getPackageName())) continue;
            candidates.add(new TaskListPolicy.TaskCandidate(
                    component.getPackageName(), task.id, displayIdOf(task)));
        }
        List<RunningApp> apps = new ArrayList<>();
        for (TaskListPolicy.SelectedApp selected : TaskListPolicy.select(candidates, widgetDisplays)) {
            apps.add(new RunningApp(selected.packageName, labelOf(context, selected.packageName),
                    selected.taskId, selected.widget));
        }
        return apps;
    }

    /** Подпись приложения; если пакет не устанавливается — его имя как есть. */
    static String labelOf(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /**
     * Пакет запущен только внутри виджета: все его задачи на дисплеях виджетов, либо задач вовсе
     * нет, но пакет числится в реестре виджетов Native. Переключать такое приложение через
     * {@code moveTaskToFront} бессмысленно — задача поднимется на самом VirtualDisplay.
     */
    static boolean isWidgetOnly(Context context, String packageName, Set<Integer> widgetDisplays,
                               boolean inWidgetRegistry) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return inWidgetRegistry;
        List<ActivityManager.RunningTaskInfo> running;
        try {
            running = manager.getRunningTasks(100);
        } catch (RuntimeException e) {
            Log.w(TAG, "isWidgetOnly: " + e.getMessage());
            return inWidgetRegistry;
        }
        List<TaskListPolicy.TaskCandidate> tasks = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : running) {
            ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
            if (component == null) continue;
            tasks.add(new TaskListPolicy.TaskCandidate(
                    component.getPackageName(), task.id, displayIdOf(task)));
        }
        return TaskListPolicy.widgetOnly(tasks, packageName, widgetDisplays, inWidgetRegistry);
    }

    /** {@code RunningTaskInfo.displayId} скрыт в этом SDK — читаем полем, как AppDisplayLauncher. */
    private static int displayIdOf(ActivityManager.RunningTaskInfo task) {
        if (!displayFieldResolved) {
            displayFieldResolved = true;
            try {
                displayField = task.getClass().getField("displayId");
            } catch (Exception e) {
                Log.w(TAG, "RunningTaskInfo.displayId недоступен: " + e.getMessage());
            }
        }
        if (displayField == null) return TaskListPolicy.UNKNOWN_DISPLAY;
        try {
            return displayField.getInt(task);
        } catch (Exception e) {
            return TaskListPolicy.UNKNOWN_DISPLAY;
        }
    }

    static List<LaunchableApp> launchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, LaunchableApp> result = new LinkedHashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String packageName = info.activityInfo.packageName;
            if (!isExternal(context, packageName) || result.containsKey(packageName)) continue;
            result.put(packageName, new LaunchableApp(packageName,
                    info.loadLabel(pm).toString(), info.loadIcon(pm)));
        }
        return new ArrayList<>(result.values());
    }

    static PendingIntent appPendingIntent(Context context, String action, String packageName, int requestCode) {
        Intent intent = new Intent(context, WidgetActionReceiver.class)
                .setAction(action).putExtra(EXTRA_PACKAGE, packageName);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void openApp(Context context, String packageName) {
        if (isExternal(context, packageName)) {
            SetModesReceiverDynamic.openFreeformApp(context.getApplicationContext(), packageName, 0);
        }
    }

    /** Обычный запуск приложения (simpleLaunch) — без VirtualDisplay, просто обычная задача. */
    static void simpleLaunch(Context context, String packageName) {
        if (isExternal(context, packageName)) {
            SetModesReceiverDynamic.openFreeformApp(context.getApplicationContext(), packageName, 0);
        }
    }

    /** Развернуть приложение на весь экран (fullscreen) — через SplitHostActivity single pane. */
    static void fullscreenLaunch(Context context, String packageName) {
        if (isExternal(context, packageName)) {
            SplitHostActivity.launchSingle(context.getApplicationContext(), packageName, 0, 0);
        }
    }

    static void stopApp(Context context, String packageName) {
        if (!isExternal(context, packageName)) return;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Method forceStop = ActivityManager.class.getMethod("forceStopPackage", String.class);
            forceStop.invoke(manager, packageName);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось закрыть " + packageName, e);
        }
    }

    /**
     * Вывести задачу приложения на передний план — переключение из диспетчера задач.
     * Задача остаётся на своём физическом дисплее (все видимые задачи у нас на display 0).
     */
    static boolean moveToFront(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty() || !isExternal(context, packageName)) return false;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        try {
            for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
                ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
                if (component != null && packageName.equals(component.getPackageName())) {
                    manager.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_NO_USER_ACTION);
                    Log.i(TAG, "moveToFront " + packageName + " task=" + task.id);
                    return true;
                }
            }
            Log.w(TAG, "moveToFront " + packageName + ": задачи нет в списке");
        } catch (Exception e) {
            Log.w(TAG, "moveToFront " + packageName + ": " + e.getMessage());
        }
        return false;
    }

    static void stopAllApps(Context context) {
        for (ApplicationInfo info : context.getPackageManager().getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !isPinned(context, info.packageName)) {
                stopApp(context, info.packageName);
            }
        }
    }

    /** Зафиксированные приложения (CSV) — их не закрывает «Закрыть все». */
    static String pinnedAppsCsv(Context context) {
        try {
            return PinnedAppsPolicy.normalizeCsv(context
                    .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getString(PREF_PINNED_APPS, ""));
        } catch (Exception e) {
            Log.w(TAG, "pinnedAppsCsv: " + e.getMessage());
            return "";
        }
    }

    static boolean isPinned(Context context, String packageName) {
        return PinnedAppsPolicy.contains(pinnedAppsCsv(context), packageName);
    }

    static void setPinned(Context context, String packageName, boolean pinned) {
        if (packageName == null || packageName.isEmpty()) return;
        String next = PinnedAppsPolicy.setPinned(pinnedAppsCsv(context), packageName, pinned);
        try {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .edit().putString(PREF_PINNED_APPS, next).apply();
            Log.i(TAG, "setPinned " + packageName + " pinned=" + pinned);
        } catch (Exception e) {
            Log.w(TAG, "setPinned: " + e.getMessage());
        }
    }

    /** Сохранить пакет приложения, запущенного вручную (не через автозапуск виджета). */
    static void saveLastManualApp(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                    .edit().putString(PREF_LAST_MANUAL_APP, packageName).apply();
            Log.i(TAG, "saveLastManualApp: " + packageName);
        } catch (Exception e) {
            Log.w(TAG, "saveLastManualApp: " + e.getMessage());
        }
    }

    /** Получить пакет последнего вручную запущенного приложения. */
    static String getLastManualApp(Context context) {
        try {
            return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                    .getString(PREF_LAST_MANUAL_APP, null);
        } catch (Exception e) {
            Log.w(TAG, "getLastManualApp: " + e.getMessage());
            return null;
        }
    }

    /** Очистить сохранённый пакет последнего вручную запущенного приложения. */
    static void clearLastManualApp(Context context) {
        try {
            context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                    .edit().remove(PREF_LAST_MANUAL_APP).apply();
            Log.i(TAG, "clearLastManualApp");
        } catch (Exception e) {
            Log.w(TAG, "clearLastManualApp: " + e.getMessage());
        }
    }

    static Bitmap iconBitmap(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    static final class RunningApp {
        final String packageName;
        final String label;
        /** id задачи (RunningTaskInfo.id) — диспетчер задач переключается по нему. */
        final int taskId;
        /** Задача живёт на дисплее виджета: на физическом экране задачи нет. */
        final boolean widget;
        RunningApp(String packageName, String label, int taskId, boolean widget) {
            this.packageName = packageName;
            this.label = label;
            this.taskId = taskId;
            this.widget = widget;
        }
    }

    static final class LaunchableApp {
        final String packageName;
        final String label;
        final Drawable icon;
        LaunchableApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName; this.label = label; this.icon = icon;
        }
    }
}