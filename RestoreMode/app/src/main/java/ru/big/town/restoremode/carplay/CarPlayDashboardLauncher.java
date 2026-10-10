package ru.big.town.restoremode.carplay;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import ru.big.town.restoremode.MainActivity;

/** Return to the dashboard task without retiring the independent CarPlay task/session. */
public final class CarPlayDashboardLauncher {
    private static volatile int dashboardDisplayId;

    private CarPlayDashboardLauncher() {}

    public static void rememberDisplay(int displayId) {
        if (displayId == 0 || displayId == 1) dashboardDisplayId = displayId;
    }

    static int targetDisplayId(int carPlayDisplayId) {
        // An embedded projection belongs to the dashboard's physical window, not its VD.
        return carPlayDisplayId == 0 || carPlayDisplayId == 1
                ? carPlayDisplayId : dashboardDisplayId;
    }

    public static boolean open(Context context, int carPlayDisplayId) {
        int displayId = targetDisplayId(carPlayDisplayId);
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        Bundle bundle = options.toBundle();
        bundle.putInt("android.activity.windowingMode", 1);
        try {
            context.startActivity(intent, bundle);
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("VoyahCarPlay", "Cannot open Dashboard display=" + displayId, unavailable);
            return false;
        }
    }
}
