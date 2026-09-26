package ru.big.town.common;

import android.content.Context;
import android.provider.Settings;

/** Installation mode is committed by the installer. A process refreshes it after restart. */
public final class InstallMode {
    public static final String KEY = "voyahtune_install_mode";
    private static volatile Context context;
    private static volatile InstallModeValue snapshot;
    private InstallMode() { }

    /** Called from Application.attachBaseContext, before providers/receivers can use the policy. */
    public static void initialize(Context application) {
        context = application;
        snapshot = null;
    }

    public static InstallModeValue current() {
        InstallModeValue known = snapshot;
        if (known != null) return known;
        Context app = context;
        if (app == null) return InstallModeValue.UNKNOWN;
        try {
            InstallModeValue mode = InstallModeValue.parse(Settings.Global.getString(app.getContentResolver(), KEY));
            // Settings may be unavailable during early boot. Retry on the next actual use.
            if (mode != InstallModeValue.UNKNOWN) snapshot = mode;
            return mode;
        } catch (RuntimeException error) {
            return InstallModeValue.UNKNOWN;
        }
    }

    public static boolean isFull() { return current() == InstallModeValue.FULL; }
}
