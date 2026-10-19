package ru.big.town.restoremode.widgets.media;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.big.town.restoremode.R;

/** Lists launchable applications which declare a media playback surface. */
final class MediaPlayerPicker {
    interface Picked {
        void onPicked(String packageName);
    }

    private static final String MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService";
    private static final String LEGACY_MUSIC_PLAYER = "android.intent.action.MUSIC_PLAYER";

    private MediaPlayerPicker() {}

    static void show(Context context, Picked callback) {
        Map<String, String> labels = candidates(context);
        if (labels.isEmpty()) {
            new MaterialAlertDialogBuilder(context, R.style.SettingsDialog)
                    .setTitle("Выбор плеера")
                    .setMessage("Не найдено приложений с поддержкой медиаплеера.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }

        List<String> packages = new ArrayList<>(labels.keySet());
        Collections.sort(
                packages,
                (first, second) -> {
                    boolean firstSystem = isSystemPackage(first);
                    boolean secondSystem = isSystemPackage(second);
                    if (firstSystem != secondSystem) {
                        return firstSystem ? 1 : -1;
                    }
                    return labels.get(first).compareToIgnoreCase(labels.get(second));
                });

        CharSequence[] items = new CharSequence[packages.size()];
        for (int index = 0; index < packages.size(); index++) {
            items[index] = labels.get(packages.get(index));
        }

        new MaterialAlertDialogBuilder(context, R.style.SettingsDialog)
                .setTitle("Выбор плеера")
                .setItems(
                        items,
                        (dialog, which) -> callback.onPicked(packages.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static Map<String, String> candidates(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Map<String, String> labels = new LinkedHashMap<>();
        collectActivities(
                packageManager,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
                labels);
        collectActivities(packageManager, new Intent(LEGACY_MUSIC_PLAYER), labels);
        collectServices(packageManager, new Intent(MEDIA_BROWSER_SERVICE), labels);
        if (labels.isEmpty()) {
            // Some players do not advertise a media category. Keep the picker usable anyway.
            collectActivities(
                    packageManager,
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    labels);
        }

        labels.keySet().removeIf(
                packageName ->
                        packageName.equals(context.getPackageName())
                                || packageName.equals("ru.big.town.anative")
                                || packageName.equals("ru.big.town.updater")
                                || packageName.equals("com.android.systemui")
                                || packageManager.getLaunchIntentForPackage(packageName) == null);
        return labels;
    }

    private static void collectActivities(
            PackageManager packageManager, Intent intent, Map<String, String> labels) {
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            labels.putIfAbsent(
                    info.activityInfo.packageName,
                    info.loadLabel(packageManager).toString());
        }
    }

    private static void collectServices(
            PackageManager packageManager, Intent intent, Map<String, String> labels) {
        List<ResolveInfo> resolved = packageManager.queryIntentServices(intent, 0);
        for (ResolveInfo info : resolved) {
            if (info.serviceInfo == null) {
                continue;
            }
            labels.putIfAbsent(
                    info.serviceInfo.packageName,
                    info.loadLabel(packageManager).toString());
        }
    }

    private static boolean isSystemPackage(String packageName) {
        return packageName.startsWith("com.qinggan")
                || packageName.startsWith("com.android")
                || packageName.startsWith("com.google")
                || packageName.startsWith("com.tencent")
                || packageName.startsWith("com.huawei")
                || packageName.startsWith("com.baidu")
                || packageName.startsWith("com.pateo");
    }
}
