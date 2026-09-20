package ru.big.town.restoremode;

import android.content.SharedPreferences;

/** Keep existing split assignments when upgrading from the split-only dock. */
final class DockLongPressAction {
    private DockLongPressAction() {}

    static String resolve(SharedPreferences prefs, int slot) {
        if (prefs.getString("dockOverride" + slot, "").isEmpty()) return "none";
        return normalize(prefs.getString("dockOverride" + slot + "LongAction", null),
                prefs.getInt("dockOverride" + slot + "Split", -1) >= 0);
    }

    static String normalize(String stored, boolean legacySplit) {
        if (stored == null) return legacySplit ? "split" : "none";
        return "cluster".equals(stored) || "split".equals(stored) ? stored : "none";
    }
}
