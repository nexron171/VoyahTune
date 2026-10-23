package ru.big.town.restoremode.carplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ru.big.town.common.CarPlayTarget;

/** Native reports physical moves without recreating the CarPlay projection on its VD. */
public final class CarPlayHostDisplayReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CarPlayTarget.ACTION_HOST_DISPLAY_CHANGED.equals(intent.getAction()))
            return;
        int projectionDisplayId = intent.getIntExtra(CarPlayTarget.EXTRA_PROJECTION_DISPLAY_ID, -1);
        int physicalDisplayId = intent.getIntExtra(CarPlayTarget.EXTRA_HOST_DISPLAY_ID, -1);
        if (projectionDisplayId > 1 && (physicalDisplayId == 0 || physicalDisplayId == 1)) {
            CarPlayDashboardLauncher.rememberHostDisplay(projectionDisplayId, physicalDisplayId);
        }
    }
}
