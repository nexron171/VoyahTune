package ru.big.town.restoremode.carplay;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.shilapi.xcertplay.DiPlayActivity;

import ru.big.town.common.DashboardTarget;

/** Resolve the physical host and open DiPlay home without retiring the projection session. */
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

    public static boolean open(Context context, int carPlayDisplayId) {
        int displayId = targetDisplayId(carPlayDisplayId);
        try {
            context.startActivity(
                    new Intent(context, DiPlayActivity.class)
                            .putExtra("page", "home")
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    DashboardTarget.options(displayId));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("VoyahCarPlay", "Cannot open DiPlay home display=" + displayId, unavailable);
            return false;
        }
    }
}
