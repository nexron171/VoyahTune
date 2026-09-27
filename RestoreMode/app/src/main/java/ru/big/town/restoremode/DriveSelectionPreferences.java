package ru.big.town.restoremode;

import android.content.SharedPreferences;
import ru.big.town.common.DriveSelectionPolicy;

/** Shared lock for the provider and settings UI in the RestoreMode process. */
final class DriveSelectionPreferences {
    static synchronized DriveSelectionPolicy read(SharedPreferences prefs) {
        return new DriveSelectionPolicy(prefs.getString("driveMode", "INDIVIDUAL"),
                prefs.getString(DriveSelectionPolicy.OVERRIDE, ""),
                prefs.getString(DriveSelectionPolicy.MEDIUM, null));
    }
    static synchronized boolean select(SharedPreferences prefs, String mode, String source) {
        DriveSelectionPolicy before = read(prefs);
        DriveSelectionPolicy after = before.select(mode, source, prefs.getBoolean("driveRememberLast", true));
        if (after == before) return false;
        prefs.edit().putString("driveMode", after.configured)
                .putString(DriveSelectionPolicy.OVERRIDE, after.override)
                .putString(DriveSelectionPolicy.MEDIUM, after.medium).apply();
        return true;
    }
}
