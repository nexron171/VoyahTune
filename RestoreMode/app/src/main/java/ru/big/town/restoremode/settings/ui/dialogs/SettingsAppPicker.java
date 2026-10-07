package ru.big.town.restoremode.settings.ui.dialogs;

import android.content.Context;
import android.content.Intent;

import ru.big.town.restoremode.R;

public final class SettingsAppPicker {
    private SettingsAppPicker() {}

    public interface AppPicked {
        void onPicked(String packageName, String label);
    }

    public static void show(Context context, String title, AppPicked callback) {
        android.content.pm.PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps =
                packageManager.queryIntentActivities(launcher, 0);

        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String packageName = ri.activityInfo.packageName;
            if (!map.containsKey(packageName)) {
                map.put(packageName, ri.loadLabel(packageManager).toString());
            }
        }

        final java.util.List<String> pkgs = new java.util.ArrayList<>(map.keySet());

        java.util.Collections.sort(
                pkgs,
                (a, b) -> {
                    boolean aIsCom = isSystemApp(a);
                    boolean bIsCom = isSystemApp(b);

                    if (aIsCom && !bIsCom) {
                        return 1;
                    }
                    if (!aIsCom && bIsCom) {
                        return -1;
                    }

                    return map.get(a).compareToIgnoreCase(map.get(b));
                });

        final CharSequence[] items = new CharSequence[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            items[i] = map.get(pkgs.get(i)) + "  ·  " + pkgs.get(i);
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        context, R.style.SettingsDialog)
                .setTitle(title)
                .setItems(
                        items,
                        (d, which) -> callback.onPicked(pkgs.get(which), map.get(pkgs.get(which))))
                .setNegativeButton("Отмена", null)
                .show();
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
