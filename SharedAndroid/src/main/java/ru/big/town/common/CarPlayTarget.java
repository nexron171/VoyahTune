package ru.big.town.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Identifies CarPlay independently from the dashboard in the same APK. */
public final class CarPlayTarget {
    public static final String PACKAGE = "ru.big.town.restoremode";
    public static final String ACTIVITY = "com.shilapi.xcertplay.DiPlayActivity";
    public static final String LAUNCHER = "ru.big.town.restoremode.carplay.CarPlayLauncherActivity";
    public static final String ID = PACKAGE + "/" + ACTIVITY;
    public static final String LABEL = "VoyahTune CarPlay";

    private CarPlayTarget() {}

    public static boolean isCarPlay(String target) {
        return ID.equals(target);
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
        if (isCarPlay(target) || PACKAGE.equals(target)) {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(PACKAGE,
                            isCarPlay(target) ? ACTIVITY : PACKAGE + ".MainActivity"));
        }
        return context.getPackageManager().getLaunchIntentForPackage(target);
    }
}
