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
    private static volatile HostDisplay hostDisplay;

    private static final class HostDisplay {
        final int virtualDisplayId;
        final int physicalDisplayId;

        HostDisplay(int virtualDisplayId, int physicalDisplayId) {
            this.virtualDisplayId = virtualDisplayId;
            this.physicalDisplayId = physicalDisplayId;
        }
    }

    private CarPlayDashboardLauncher() {}

    public static void rememberDisplay(int displayId) {
        if (displayId == 0 || displayId == 1) dashboardDisplayId = displayId;
    }

    static void rememberHostDisplay(int virtualDisplayId, int physicalDisplayId) {
        if (virtualDisplayId <= 1) {
            hostDisplay = null;
        } else if (physicalDisplayId == 0 || physicalDisplayId == 1) {
            hostDisplay = new HostDisplay(virtualDisplayId, physicalDisplayId);
        }
    }

    static int targetDisplayId(int carPlayDisplayId) {
        HostDisplay host = hostDisplay;
        if (host != null && host.virtualDisplayId == carPlayDisplayId) {
            return host.physicalDisplayId;
        }
        return carPlayDisplayId == 0 || carPlayDisplayId == 1
                ? carPlayDisplayId
                : dashboardDisplayId;
    }

    public static boolean open(Context context, int carPlayDisplayId) {
        int displayId = targetDisplayId(carPlayDisplayId);
        Intent intent =
                new Intent(context, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
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
