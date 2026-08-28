package com.qinggan.app.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Synthetic OD transfer/navigation lifecycle surface used by launcherdock.js. */
public class LauncherModel extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Stub.
    }

    public void handleUpdateMainNavigationBar(
            String packageName, String activityName, boolean visible) {
        // Stub.
    }

    public void handleUpdateSecondNavigationBar(
            String packageName, String activityName, boolean visible) {
        // Stub.
    }

    public void onMoveStart(
            String packageName,
            String objectName,
            int type,
            int displayId,
            int posX,
            int posY) {
        // Stub.
    }

    public void onMoveStop(
            String packageName,
            String objectName,
            int type,
            int displayId,
            int posX,
            int posY) {
        // Stub.
    }
}
