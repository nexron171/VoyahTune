package ru.big.town.restoremode.carplay;

import android.content.Context;
import android.util.Log;

import ru.big.town.common.DashboardTarget;
import ru.big.town.restoremode.MainActivity;
import ru.big.town.restoremode.passenger.PassengerDashboardActivity;

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
        if (displayId == 0 || displayId == 1) {
            dashboardDisplayId = displayId;
        }
    }

    static void rememberHostDisplay(int virtualDisplayId, int physicalDisplayId) {
        if (virtualDisplayId <= 1) {
            hostDisplay = null;
        } else if (physicalDisplayId == 0 || physicalDisplayId == 1) {
            hostDisplay = new HostDisplay(virtualDisplayId, physicalDisplayId);
        }
    }

    static void rememberInitialHostDisplay(int virtualDisplayId, int physicalDisplayId) {
        HostDisplay host = hostDisplay;
        if (host == null || host.virtualDisplayId != virtualDisplayId) {
            rememberHostDisplay(virtualDisplayId, physicalDisplayId);
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

    static Class<? extends android.app.Activity> dashboardActivityClass(int displayId) {
        return displayId == 1 ? PassengerDashboardActivity.class : MainActivity.class;
    }

    public static boolean open(Context context, int carPlayDisplayId) {
        int displayId = targetDisplayId(carPlayDisplayId);
        try {
            context.startActivity(
                    DashboardTarget.intent(displayId), DashboardTarget.options(displayId));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("VoyahCarPlay", "Cannot open Dashboard display=" + displayId, unavailable);
            return false;
        }
    }
}
