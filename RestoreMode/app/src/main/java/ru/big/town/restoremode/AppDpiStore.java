package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Per-app DPI: карта «пакет → densityDpi» в DrivePreferences ("appDpi", JSON-объект).
 * 0 / отсутствие ключа = авто (дефолтный DPI дисплея). Значение берётся при открытии сплита и
 * применяется к виртуальному дисплею того окна, где запущено это приложение.
 */
public class AppDpiStore {

    static final String KEY = "appDpi";

    /** DPI приложения или 0 (авто), если не задан. */
    static int get(SharedPreferences p, String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        try {
            return new JSONObject(p.getString(KEY, "{}")).optInt(pkg, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Задать DPI приложения. dpi<=0 удаляет запись (авто). */
    static void set(SharedPreferences p, String pkg, int dpi) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(p.getString(KEY, "{}"));
            if (dpi > 0) o.put(pkg, dpi); else o.remove(pkg);
            p.edit().putString(KEY, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
