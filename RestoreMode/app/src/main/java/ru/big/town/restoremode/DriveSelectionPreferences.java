package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.os.Bundle;
import ru.big.town.common.DriveSelectionPolicy;

/** Serializes durable target changes and hook bootstrap claims across provider Binder threads. */
final class DriveSelectionPreferences {
    private static final String REV = "driveRevision", CYCLE = "driveCycle", ACC = "driveAcc";
    private static final String BOOT = "driveBoot", START = "driveStartup";
    static synchronized DriveSelectionPolicy read(SharedPreferences prefs) {
        return new DriveSelectionPolicy(prefs.getString("driveMode", "INDIVIDUAL"),
                prefs.getString(DriveSelectionPolicy.OVERRIDE, ""),
                prefs.getString(DriveSelectionPolicy.MEDIUM, null),
                prefs.getString(DriveSelectionPolicy.CURRENT, ""));
    }
    static synchronized boolean select(SharedPreferences prefs, String mode, String source) {
        DriveSelectionPolicy before = read(prefs);
        DriveSelectionPolicy after = before.select(mode, source, prefs.getBoolean("driveRememberLast", true));
        if (after == before) return false;
        return prefs.edit().putString("driveMode", after.configured)
                .putString(DriveSelectionPolicy.OVERRIDE, after.override)
                .putString(DriveSelectionPolicy.MEDIUM, after.medium)
                .putString(DriveSelectionPolicy.CURRENT, after.current)
                .putLong(REV, prefs.getLong(REV, 0) + 1)
                .putString(START, "user").commit();
    }
    static synchronized Bundle hook(SharedPreferences prefs, String action, Bundle args, int boot) {
        if (args == null) args = new Bundle();
        // Only CAN's actual ACC observation changes the trip boundary. A Native restart does not.
        if ("acc".equals(action)) {
            int acc = args.getInt("acc", -1);
            if (acc == 0 || acc == 2) {
                int previous = prefs.getInt(ACC, -1);
                boolean newBoot = prefs.getInt(BOOT, -1) != boot;
                boolean next = (newBoot || previous == 0) && acc == 2;
                SharedPreferences.Editor e = prefs.edit().putInt(BOOT, boot).putInt(ACC, acc);
                if (newBoot || next) {
                    // A first observation in an already-running trip must retain recorded intent.
                    boolean preserve = prefs.getInt(BOOT, -1) == -1 && previous == -1;
                    e.putLong(CYCLE, prefs.getLong(CYCLE, 0) + 1)
                            .putLong(REV, prefs.getLong(REV, 0) + 1);
                    if (!preserve) e.putString(DriveSelectionPolicy.CURRENT, "").putString(START, "pending");
                }
                if (!e.commit()) throw new IllegalStateException("ACC state not persisted");
            }
        } else if ("user".equals(action)) {
            if (!select(prefs, args.getString("mode"), DriveSelectionPolicy.EXPLICIT)) {
                throw new IllegalArgumentException("Invalid user mode");
            }
        } else if ("claim".equals(action)) {
            String state = prefs.getString(START, "pending");
            boolean claim = "pending".equals(state) && prefs.getInt(ACC, -1) == 2
                    && args.getLong("revision", -1) == prefs.getLong(REV, 0)
                    && prefs.getBoolean("driveEnabled", false) && !prefs.getBoolean("debugMode", false);
            if (claim && !prefs.edit().putString(START, "claimed").commit()) {
                throw new IllegalStateException("Startup claim not persisted");
            }
            Bundle result = snapshot(prefs);
            result.putBoolean("claimed", claim);
            return result;
        } else if ("complete".equals(action)) {
            if (args.getLong("revision", -1) == prefs.getLong(REV, 0)
                    && "claimed".equals(prefs.getString(START, "pending"))) {
                if (!prefs.edit().putString(START, args.getBoolean("accepted") ? "submitted" : "uncertain").commit()) {
                    throw new IllegalStateException("Startup result not persisted");
                }
            }
        } else if (!"snapshot".equals(action)) {
            throw new IllegalArgumentException("Unknown drive hook operation");
        }
        return snapshot(prefs);
    }
    private static Bundle snapshot(SharedPreferences prefs) {
        Bundle result = new Bundle();
        result.putInt("protocol", 2);
        result.putString("mode", read(prefs).effective());
        result.putLong("revision", prefs.getLong(REV, 0));
        result.putLong("cycle", prefs.getLong(CYCLE, 0));
        result.putInt("acc", prefs.getInt(ACC, -1));
        result.putString("startup", prefs.getString(START, "pending"));
        result.putBoolean("enabled", prefs.getBoolean("driveEnabled", false));
        result.putBoolean("debug", prefs.getBoolean("debugMode", false));
        return result;
    }
}
