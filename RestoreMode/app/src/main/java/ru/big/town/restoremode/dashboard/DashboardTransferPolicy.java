package ru.big.town.restoremode.dashboard;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import ru.big.town.common.CarPlayTarget;

/** Uses the OEM activity-level move gate without denying the shared CarPlay package. */
public final class DashboardTransferPolicy {
    private static boolean unavailableReported;

    private DashboardTransferPolicy() {}

    static boolean shouldPin(String activityName) {
        return activityName != null
                && activityName.startsWith("ru.big.town.restoremode.")
                && !CarPlayTarget.LAUNCHER.equals(activityName);
    }

    public static void apply(Activity activity) {
        if (!shouldPin(activity.getClass().getName())) {
            return;
        }
        try {
            Class<?> managerClass = Class.forName("com.qinggan.multidisplay.MultiDisplayManager");
            Object manager =
                    managerClass.getMethod("getInstance", Context.class).invoke(null, activity);
            managerClass
                    .getMethod(
                            "setEnableActivityAnimation",
                            Activity.class,
                            boolean.class,
                            boolean.class)
                    .invoke(manager, activity, false, true);
        } catch (ReflectiveOperationException | LinkageError error) {
            if (!unavailableReported) {
                unavailableReported = true;
                Log.w("VoyahDashboard", "OEM activity transfer gate unavailable", error);
            }
        }
    }
}
