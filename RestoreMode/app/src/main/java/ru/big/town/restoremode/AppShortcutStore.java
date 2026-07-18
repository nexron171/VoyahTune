package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Список приложений-ярлыков для главного экрана (плитки как у сплита, но запуск обычный).
 * Хранится JSON-массивом имён пакетов под ключом «appShortcuts» в DrivePreferences.
 */
class AppShortcutStore {
    private static final String KEY = "appShortcuts";

    static List<String> load(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(SharedPreferences p, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        p.edit().putString(KEY, a.toString()).apply();
    }
}
