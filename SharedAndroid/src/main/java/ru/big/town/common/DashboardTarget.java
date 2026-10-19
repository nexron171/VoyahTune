package ru.big.town.common;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

/** Distinct task roots keep the two physical dashboards independent. */
public final class DashboardTarget {
    public static final String PACKAGE = "ru.big.town.restoremode";
    public static final String DRIVER = PACKAGE + ".MainActivity";
    public static final String PASSENGER = PACKAGE + ".passenger.PassengerDashboardActivity";
    public static final String ACTION_OPEN = PACKAGE + ".OPEN_DASHBOARD";

    private DashboardTarget() {}

    public static String activityForDisplay(int displayId) {
        return displayId == 1 ? PASSENGER : DRIVER;
    }

    public static boolean isPhysicalDashboard(String target, int displayId) {
        return PACKAGE.equals(target) && (displayId == 0 || displayId == 1);
    }

    public static Intent intent(int displayId) {
        return new Intent(ACTION_OPEN)
                .setClassName(PACKAGE, activityForDisplay(displayId))
                .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public static Bundle options(int displayId) {
        ActivityOptions options =
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId == 1 ? 1 : 0);
        Bundle bundle = options.toBundle();
        bundle.putInt("android.activity.windowingMode", 1);
        return bundle;
    }
}
