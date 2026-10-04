package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.os.Bundle;
import ru.big.town.common.DriveSelectionPolicy;

/** Serializes durable target changes and hook bootstrap claims across provider Binder threads. */
final class DriveSelectionPreferences {
    private static final String REV = "driveRevision", CYCLE = "driveCycle", ACC = "driveAcc";
    private static final String BOOT = "driveBoot", START = "driveStartup";
    private static final String SETTINGS_START = "settingsStartup";
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
                // A recorded user choice supersedes pending/claimed startup restore in this trip.
                .putString(START, "selected")
                .putLong(REV, prefs.getLong(REV, 0) + 1)
                .commit();
    }
    static synchronized boolean selectEnergy(SharedPreferences prefs, String mode, boolean settings) {
        if (!validEnergy(mode)) return false;
        SharedPreferences.Editor e = prefs.edit().putString("currentTripEnergy", mode).putBoolean("forcedEv", "FORCE_EV".equals(mode))
                .putString(START, "selected").putLong(REV, prefs.getLong(REV, 0) + 1);
        if (!"FORCE_EV".equals(mode) && (settings || prefs.getBoolean("energyRememberLast", true))) e.putString("energy", mode);
        return e.commit();
    }
    static boolean validEnergy(String mode) {
        return "SMART".equals(mode) || "EV".equals(mode) || "REV".equals(mode)
                || "SREV".equals(mode) || "FORCE_EV".equals(mode);
    }

    /** Native's PI restore boundary has no CAN-agent ACC claim; retain saved/widget targets. */
    static synchronized boolean beginNativeRestore(SharedPreferences prefs) {
        return prefs.edit().putString(DriveSelectionPolicy.CURRENT, "")
                .putString("currentTripEnergy", "")
                .putLong(REV, prefs.getLong(REV, 0) + 1).commit();
    }
    static synchronized String energy(SharedPreferences prefs) {
        String current = prefs.getString("currentTripEnergy", "");
        return validEnergy(current) ? current : prefs.getString("energy", "SREV");
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
                    e.putString(SETTINGS_START, "pending");
                    if (!preserve) e.putString(DriveSelectionPolicy.CURRENT, "").putString("currentTripEnergy", "").putString(START, "pending");
                }
                if (!e.commit()) throw new IllegalStateException("ACC state not persisted");
            }
        } else if ("nativeRestore".equals(action)) {
            if (!beginNativeRestore(prefs)) throw new IllegalStateException("Native restore state not persisted");
        } else if ("claimSettings".equals(action)) {
            boolean claim = "pending".equals(prefs.getString(SETTINGS_START, "idle"))
                    && prefs.getInt(ACC, -1) == 2;
            if (claim && !prefs.edit().putString(SETTINGS_START, "claimed").commit()) {
                throw new IllegalStateException("Settings claim not persisted");
            }
            Bundle result = snapshot(prefs);
            result.putBoolean("claimed", claim);
            return result;
        } else if ("completeSettings".equals(action)) {
            if (args.getLong("cycle", -1) == prefs.getLong(CYCLE, 0)
                    && "claimed".equals(prefs.getString(SETTINGS_START, "idle"))) {
                if (!prefs.edit().putString(SETTINGS_START,
                        args.getBoolean("accepted") ? "submitted" : "uncertain").commit()) {
                    throw new IllegalStateException("Settings result not persisted");
                }
            }
        } else if ("manual".equals(action)) {
            if (prefs.getBoolean("driveEnabled", false)) select(prefs, prefs.getString("driveMode", "INDIVIDUAL"), DriveSelectionPolicy.SETTINGS);
            if (prefs.getBoolean("energyEnabled", false) || prefs.getBoolean("forcedEv", false))
                selectEnergy(prefs, prefs.getBoolean("forcedEv", false) ? "FORCE_EV" : prefs.getString("energy", "SREV"), true);
        } else if ("user".equals(action)) {
            if (args.containsKey("mode") && !select(prefs, args.getString("mode"), DriveSelectionPolicy.EXPLICIT))
                throw new IllegalArgumentException("Invalid user drive mode");
            if (args.containsKey("energy") && !selectEnergy(prefs, args.getString("energy"), false))
                throw new IllegalArgumentException("Invalid user energy mode");
        } else if ("claim".equals(action)) {
            String state = prefs.getString(START, "pending");
            boolean claim = "pending".equals(state) && prefs.getInt(ACC, -1) == 2
                    && args.getLong("revision", -1) == prefs.getLong(REV, 0)
                    && (prefs.getBoolean("driveEnabled", false) || prefs.getBoolean("energyEnabled", false)
                        || prefs.getBoolean("forcedEv", false));
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
        result.putString("energy", energy(prefs));
        result.putString("configuredEnergy", prefs.getString("energy", "SREV"));
        result.putLong("revision", prefs.getLong(REV, 0));
        result.putLong("cycle", prefs.getLong(CYCLE, 0));
        result.putInt("acc", prefs.getInt(ACC, -1));
        result.putString("startup", prefs.getString(START, "pending"));
        result.putString("settingsStartup", prefs.getString(SETTINGS_START, "idle"));
        result.putBoolean("enabled", prefs.getBoolean("driveEnabled", false));
        return result;
    }
}
