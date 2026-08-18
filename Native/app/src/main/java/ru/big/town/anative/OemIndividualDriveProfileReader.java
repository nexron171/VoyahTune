package ru.big.town.anative;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Reads the same per-account Individual-mode settings used by the stock VehicleSetting app. */
final class OemIndividualDriveProfileReader {
    private static final String TAG = "$$$ IndividualProfile $$$";
    private static final String ACCOUNT_INFO_PATH = "/private/configs/token/accountInfo";
    private static final String OEM_GLOBAL_SETTINGS_URI =
            "content://qinggan.settings/global";
    private static final String[] VALUE_PROJECTION = {"value"};
    private static final String STEERING_PREFIX = "drive_mode_steeringWheelAssist";
    private static final String ACCELERATOR_PREFIX = "drive_mode_runState";

    private OemIndividualDriveProfileReader() {}

    static DriveModeCanPolicy.IndividualProfile read(Context context) {
        if (context == null) return null;
        String accountId = readCurrentAccountId();
        if (accountId == null) {
            Log.e(TAG, "Cannot identify current OEM account; Individual mode not sent");
            return null;
        }
        try {
            SettingRead steering = readOemGlobal(
                    context.getContentResolver(), STEERING_PREFIX + accountId);
            SettingRead accelerator = readOemGlobal(
                    context.getContentResolver(), ACCELERATOR_PREFIX + accountId);
            if (!steering.success || !accelerator.success) {
                Log.e(TAG, "Cannot access OEM settings provider; Individual mode not sent");
                return null;
            }
            DriveModeCanPolicy.IndividualProfile profile =
                    parseProfile(steering.value, accelerator.value);
            if (profile == null) {
                Log.e(TAG, "Invalid OEM Individual profile; mode not sent");
            } else {
                Log.i(TAG, "OEM Individual profile loaded: steering=" + profile.steering
                        + " accelerator=" + profile.accelerator
                        + " account=" + ("guest".equals(accountId) ? "guest" : "user"));
            }
            return profile;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot read OEM Individual profile", e);
            return null;
        }
    }

    static DriveModeCanPolicy.IndividualProfile parseProfile(
            String steeringRaw, String acceleratorRaw) {
        Integer steering = parseIntOrDefault(steeringRaw, 2);
        Integer accelerator = parseIntOrDefault(acceleratorRaw, 1);
        if (steering == null || accelerator == null) return null;
        return DriveModeCanPolicy.IndividualProfile.validated(steering, accelerator);
    }

    static String parseAccountId(String payload) {
        if (payload == null) return null;
        String value = payload.trim();
        if (value.isEmpty()) return null;
        if ("guest".equals(value)) return "guest";
        String[] fields = value.split("%", -1);
        if (fields.length < 5) return null;
        String accountId = fields[fields.length - 2].trim();
        return accountId.isEmpty() ? null : accountId;
    }

    private static String readCurrentAccountId() {
        File file = new File(ACCOUNT_INFO_PATH);
        if (!file.isFile()) {
            Log.e(TAG, "OEM accountInfo file unavailable");
            return null;
        }
        StringBuilder payload = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) payload.append(line);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Cannot read OEM accountInfo", e);
            return null;
        }
        return parseAccountId(payload.toString());
    }

    private static SettingRead readOemGlobal(ContentResolver resolver, String key) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(Uri.parse(OEM_GLOBAL_SETTINGS_URI), VALUE_PROJECTION,
                    "name=?", new String[]{key}, null);
            if (cursor == null) return SettingRead.failed();
            return SettingRead.success(cursor.moveToNext() ? cursor.getString(0) : null);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot read OEM setting " + key, e);
            return SettingRead.failed();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static Integer parseIntOrDefault(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class SettingRead {
        final boolean success;
        final String value;

        private SettingRead(boolean success, String value) {
            this.success = success;
            this.value = value;
        }

        static SettingRead success(String value) {
            return new SettingRead(true, value);
        }

        static SettingRead failed() {
            return new SettingRead(false, null);
        }
    }
}
