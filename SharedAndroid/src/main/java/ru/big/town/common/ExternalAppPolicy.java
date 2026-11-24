package ru.big.town.common;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Applications that VoyahTune can stop before moving them to another display. */
public final class ExternalAppPolicy {
    private final String ownPackage;
    private final String homePackage;
    private final PackageManager manager;

    public ExternalAppPolicy(Context context) {
        ownPackage = context.getPackageName();
        manager = context.getPackageManager();
        ResolveInfo home =
                manager.resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        homePackage =
                home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
    }

    public boolean canStop(String packageName) {
        if (packageName == null
                || packageName.equals(ownPackage)
                || packageName.equals(homePackage)
                || packageName.equals("ru.big.town.anative")
                || packageName.equals("ru.big.town.restoremode")) {
            return false;
        }
        if (packageName.equals("com.android.contacts")
                || packageName.equals("com.android.car.dialer")) return true;
        if (packageName.startsWith("com.qinggan") || packageName.startsWith("com.android.car"))
            return false;
        try {
            return (manager.getApplicationInfo(packageName, 0).flags & ApplicationInfo.FLAG_SYSTEM)
                    == 0;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }
}
