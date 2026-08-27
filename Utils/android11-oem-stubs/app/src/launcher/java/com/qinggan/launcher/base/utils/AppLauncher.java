package com.qinggan.launcher.base.utils;

import android.content.Context;
import android.content.Intent;

/** Synthetic physical-display launch entry point used by launcherdock.js. */
public final class AppLauncher {
    private AppLauncher() {
    }

    public static void startApp(Context context, Intent intent, int screenId) {
        // Stub. Never starts an activity from the harness.
    }
}
