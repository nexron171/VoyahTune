package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Пакеты, которые на физических экранах должны обходить оконный режим и открываться без полей.
 * Авторитетный список хранится JSON-массивом в DrivePreferences и event-driven зеркалится в Native.
 */
final class FullscreenAppStore {
    static final String KEY = "fullscreenApps";

    private FullscreenAppStore() {}

    static List<String> load(SharedPreferences prefs) {
        return decode(prefs.getString(KEY, "[]"));
    }

    static void save(SharedPreferences prefs, List<String> packages) {
        prefs.edit().putString(KEY, encode(packages)).apply();
    }

    static String snapshotCsv(SharedPreferences prefs) {
        return android.text.TextUtils.join(",", load(prefs));
    }

    static List<String> decode(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < values.length(); i++) {
                String pkg = values.optString(i, "").trim();
                if (!pkg.isEmpty() && !out.contains(pkg)) out.add(pkg);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String encode(List<String> packages) {
        JSONArray values = new JSONArray();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg != null && !pkg.trim().isEmpty()) values.put(pkg.trim());
            }
        }
        return values.toString();
    }
}
