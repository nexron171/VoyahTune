package ru.big.town.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Identifies CarPlay independently from the dashboard in the same APK. */
public final class CarPlayTarget {
    public static final String PACKAGE = "ru.big.town.restoremode";
    public static final String ACTIVITY = "com.shilapi.xcertplay.DiPlayActivity";
    public static final String ENTRY = "com.shilapi.xcertplay.CarPlayEntryActivity";
    public static final String PROJECTION = "com.shilapi.xcertplay.CarPlayHostActivity";
    public static final String LAUNCHER = "ru.big.town.restoremode.carplay.CarPlayLauncherActivity";
    public static final String ID = PACKAGE + "/" + ACTIVITY;
    public static final String LABEL = "VoyahTune CarPlay";
    public static final String ENABLED = "carPlayEnabled";
    public static final String EXTRA_HOST_DISPLAY_ID = "carPlayHostDisplayId";
    public static final String EXTRA_PROJECTION_DISPLAY_ID = "carPlayProjectionDisplayId";
    public static final String ACTION_HOST_DISPLAY_CHANGED =
            PACKAGE + ".CARPLAY_HOST_DISPLAY_CHANGED";
    public static final String HOST_DISPLAY_RECEIVER =
            PACKAGE + ".carplay.CarPlayHostDisplayReceiver";

    private CarPlayTarget() {}

    public static boolean isCarPlay(String target) {
        return ID.equals(target);
    }

    /** PackageManager exposes the persisted feature gate to Native without starting RestoreMode. */
    public static boolean isEnabled(Context context) {
        try {
            return context.getPackageManager()
                    .getActivityInfo(new ComponentName(PACKAGE, LAUNCHER), 0).enabled;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return false;
        }
    }

    /** Only a live Native-owned CarPlay VD can receive a projection request. */
    public static boolean canProjectOnDisplay(int requested, int owned, String target) {
        return requested > 1 && requested == owned && isCarPlay(target);
    }

    public static boolean opensActivity(String target, int width, int height) {
        return isCarPlay(target) && width == 1 && height == 1;
    }

    public static boolean matches(String target, String packageName, String activityName) {
        if (!isCarPlay(target)) {
            return target != null && target.equals(packageName);
        }
        return PACKAGE.equals(packageName)
                && activityName != null
                && activityName.startsWith("com.shilapi.xcertplay.");
    }

    public static Intent launchIntent(Context context, String target) {
        return launchIntent(context, target, 0);
    }

    /** Saved targets stay stable; embedded CarPlay opens the projection, not its home screen. */
    public static String activityForDisplay(String target, int displayId) {
        if (isCarPlay(target)) {
            return displayId > 1 ? PROJECTION : ENTRY;
        }
        return PACKAGE.equals(target) ? DashboardTarget.activityForDisplay(displayId) : null;
    }

    public static Intent launchIntent(Context context, String target, int displayId) {
        if (isCarPlay(target) && !isEnabled(context)) {
            return null;
        }
        if (DashboardTarget.isPhysicalDashboard(target, displayId)) {
            return DashboardTarget.intent(displayId);
        }
        String activity = activityForDisplay(target, displayId);
        if (activity != null) {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(PACKAGE, activity));
        }
        return context.getPackageManager().getLaunchIntentForPackage(target);
    }
}
