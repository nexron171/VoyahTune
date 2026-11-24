package ru.big.town.restoremode.settings.ui.dialogs;

import android.content.Context;
import android.content.Intent;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.ExternalAppPolicy;
import ru.big.town.restoremode.R;

import java.util.function.Predicate;

public final class SettingsAppPicker {
    private SettingsAppPicker() {}

    public interface AppPicked {
        void onPicked(String packageName, String label);
    }

    public static void show(Context context, String title, AppPicked callback) {
        show(context, title, false, callback);
    }

    public static void show(
            Context context, String title, boolean includeCarPlay, AppPicked callback) {
        show(context, title, includeCarPlay, packageName -> true, callback);
    }

    static void showExternal(Context context, String title, AppPicked callback) {
        show(context, title, false, new ExternalAppPolicy(context)::canStop, callback);
    }

    private static void show(
            Context context,
            String title,
            boolean includeCarPlay,
            Predicate<String> selectable,
            AppPicked callback) {
        android.content.pm.PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps =
                packageManager.queryIntentActivities(launcher, 0);

        java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo activity : apps) {
            if (CarPlayTarget.PACKAGE.equals(activity.activityInfo.packageName)
                    && CarPlayTarget.LAUNCHER.equals(activity.activityInfo.name)) {
                continue;
            }
            String packageName = activity.activityInfo.packageName;
            if (!labels.containsKey(packageName) && selectable.test(packageName)) {
                labels.put(packageName, activity.loadLabel(packageManager).toString());
            }
        }

        if (includeCarPlay && ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(context)) {
            labels.put(CarPlayTarget.ID, CarPlayTarget.LABEL);
        }

        final java.util.List<String> packages = new java.util.ArrayList<>(labels.keySet());

        java.util.Collections.sort(
                packages,
                (first, second) -> {
                    boolean firstSystem = isSystemApp(first);
                    boolean secondSystem = isSystemApp(second);

                    if (firstSystem && !secondSystem) {
                        return 1;
                    }
                    if (!firstSystem && secondSystem) {
                        return -1;
                    }

                    return labels.get(first).compareToIgnoreCase(labels.get(second));
                });

        final CharSequence[] items = new CharSequence[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            items[i] = labels.get(packages.get(i)) + "  ·  " + packages.get(i);
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder dialogBuilder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                context, R.style.SettingsDialog)
                        .setTitle(title)
                        .setNegativeButton("Отмена", null);
        if (items.length == 0) {
            dialogBuilder.setMessage("Нет доступных приложений");
        } else {
            dialogBuilder.setItems(
                    items,
                    (dialog, which) -> {
                        String packageName = packages.get(which);
                        if (CarPlayTarget.isCarPlay(packageName)
                                && !ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(
                                        context)) {
                            return;
                        }
                        callback.onPicked(packageName, labels.get(packageName));
                    });
        }
        dialogBuilder.show();
    }

    private static boolean isSystemApp(String packageName) {
        return packageName.startsWith("com.qinggan")
                || packageName.startsWith("com.bz")
                || packageName.startsWith("com.android")
                || packageName.startsWith("com.tencent")
                || packageName.startsWith("com.huawei")
                || packageName.startsWith("com.mega")
                || packageName.startsWith("com.thunder")
                || packageName.startsWith("com.pateo")
                || packageName.startsWith("com.baidu")
                || packageName.startsWith("com.richauto");
    }
}
