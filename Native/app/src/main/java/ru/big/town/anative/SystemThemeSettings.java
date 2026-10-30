package ru.big.town.anative;

import android.app.UiModeManager;
import android.content.Context;
import android.util.Log;

/** Applies the system mode through the service that owns the live UI configuration. */
final class SystemThemeSettings {
    private static final String TAG = "SystemThemeSettings";

    private SystemThemeSettings() {}

    static void apply(Context context, int requestedMode) {
        int mode =
                requestedMode >= 0 && requestedMode <= 3
                        ? requestedMode
                        : UiModeManager.MODE_NIGHT_AUTO;
        try {
            UiModeManager manager = context.getSystemService(UiModeManager.class);
            if (manager == null) {
                Log.e(TAG, "UiModeManager unavailable; requested=" + mode);
                return;
            }
            // The service persists the mode itself; a secure-setting write alone is not applied.
            manager.setNightMode(mode);
            int actualMode = manager.getNightMode();
            if (actualMode != mode) {
                Log.e(
                        TAG,
                        "System theme rejected: requested=" + mode + " actual=" + actualMode
                                + "; check MODIFY_DAY_NIGHT_MODE grant");
                return;
            }
            Log.i(TAG, "System theme applied: mode=" + actualMode);
        } catch (RuntimeException error) {
            Log.e(TAG, "System theme failed: requested=" + mode, error);
        }
    }
}
