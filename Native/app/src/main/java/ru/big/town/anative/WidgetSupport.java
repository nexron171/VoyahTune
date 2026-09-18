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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WidgetSupport {
    private static final String TAG = "NativeWidgets";
    // Ключ SharedPreferences для сохранения пакета вручную запущенного приложения.
    // Используется для автозапуска при повторном открытии MainActivity (когда у плитки отключён автозапуск).
    static final String PREF_LAST_MANUAL_APP = "lastManualApp";
    static final String EXTRA_PACKAGE = "package";
    static final String ACTION_OPEN_APP = "ru.big.town.anative.WIDGET_OPEN_APP";
    static final String ACTION_CLOSE_APP = "ru.big.town.anative.WIDGET_CLOSE_APP";
    static final String ACTION_CLOSE_ALL = "ru.big.town.anative.WIDGET_CLOSE_ALL";
    // Обычный запуск приложения (simpleLaunch) — без VirtualDisplay/сплита,
    // просто обычная задача на физическом экране display 0.
    static final String ACTION_SIMPLE_LAUNCH = "ru.big.town.anative.WIDGET_SIMPLE_LAUNCH";
    // Развернуть приложение на весь экран (fullscreen) — через SplitHostActivity single pane.
    static final String ACTION_FULLSCREEN_LAUNCH = "ru.big.town.anative.WIDGET_FULLSCREEN_LAUNCH";

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

    static List<RunningApp> runningApps(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Map<String, RunningApp> result = new LinkedHashMap<>();
        if (manager == null) return new ArrayList<>();
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
            if (component == null || !isExternal(context, component.getPackageName())) continue;
            String packageName = component.getPackageName();
            if (!result.containsKey(packageName)) {
                CharSequence label;
                try {
                    label = context.getPackageManager().getApplicationLabel(
                            context.getPackageManager().getApplicationInfo(packageName, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    label = packageName;
                }
                result.put(packageName, new RunningApp(packageName, label.toString()));
            }
        }
        return new ArrayList<>(result.values());
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

    static void stopAllApps(Context context) {
        for (ApplicationInfo info : context.getPackageManager().getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) stopApp(context, info.packageName);
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
        RunningApp(String packageName, String label) { this.packageName = packageName; this.label = label; }
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