package ru.big.town.restoremode.widgets.apps;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import ru.big.town.restoremode.vehicle.DriveSelectionPreferences;

/**
 * Session-scoped launch memory for embedded application tiles of the main screen.
 *
 * <p>The session is derived without Native changes from the boot counter and the ACC cycle already
 * persisted by the drive-selection provider. This keeps automatic launches from repeating on every
 * screen resume and lets manually opened applications return in the same vehicle session.</p>
 */
public final class AppWidgetSessionStore {
    private static final String KEY_AUTO_START_SESSION = "appWidgetAutoStartSession";
    private static final String KEY_LAUNCHES = "appWidgetSessionLaunches";
    private static final String KEY_SESSION = "session";
    private static final String KEY_LAUNCH_ITEMS = "launches";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_DPI = "dpi";

    private AppWidgetSessionStore() {}

    /** Per-tile application launch remembered for the current vehicle session. */
    public static final class Launch {
        public final String packageName;
        public final int dpi;

        Launch(String packageName, int dpi) {
            this.packageName = packageName;
            this.dpi = AppWidgetStore.normalizeDpi(dpi);
        }
    }

    /** Stable session key for the current head-unit boot and ACC cycle. */
    public static String sessionId(Context context, SharedPreferences preferences) {
        int bootCount = 0;
        try {
            bootCount =
                    Settings.Global.getInt(
                            context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        } catch (RuntimeException ignored) {
        }
        return bootCount + ":" + DriveSelectionPreferences.cycle(preferences);
    }

    /** True until automatic launches have been handled for this session. */
    public static boolean isFirstOpening(SharedPreferences preferences, String sessionId) {
        return !sessionId.equals(preferences.getString(KEY_AUTO_START_SESSION, ""));
    }

    /** Mark automatic launches as handled for this session. */
    public static void markOpeningHandled(SharedPreferences preferences, String sessionId) {
        preferences.edit().putString(KEY_AUTO_START_SESSION, sessionId).apply();
    }

    /** Remember the application that was launched in a tile during this session. */
    public static void saveLaunch(
            SharedPreferences preferences,
            String sessionId,
            String widgetId,
            String packageName,
            int dpi) {
        if (widgetId == null
                || widgetId.isEmpty()
                || packageName == null
                || packageName.isEmpty()) {
            return;
        }
        JSONObject root = readSession(preferences, sessionId);
        try {
            JSONObject launches = root.optJSONObject(KEY_LAUNCH_ITEMS);
            if (launches == null) {
                launches = new JSONObject();
                root.put(KEY_LAUNCH_ITEMS, launches);
            }
            JSONObject launch = new JSONObject();
            launch.put(KEY_PACKAGE, packageName);
            launch.put(KEY_DPI, AppWidgetStore.normalizeDpi(dpi));
            launches.put(widgetId, launch);
            preferences.edit().putString(KEY_LAUNCHES, root.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Application launched in the tile during this session, or {@code null}. */
    public static Launch findLaunch(
            SharedPreferences preferences, String sessionId, String widgetId) {
        JSONObject root = readSession(preferences, sessionId);
        JSONObject launches = root.optJSONObject(KEY_LAUNCH_ITEMS);
        JSONObject launch = launches == null ? null : launches.optJSONObject(widgetId);
        if (launch == null) {
            return null;
        }
        String packageName = launch.optString(KEY_PACKAGE, "");
        if (packageName.isEmpty()) {
            return null;
        }
        return new Launch(packageName, launch.optInt(KEY_DPI, AppWidgetStore.DEFAULT_DPI));
    }

    /** Forget a tile after the user explicitly closes its embedded application. */
    public static void forgetLaunch(
            SharedPreferences preferences, String sessionId, String widgetId) {
        JSONObject root = readSession(preferences, sessionId);
        JSONObject launches = root.optJSONObject(KEY_LAUNCH_ITEMS);
        if (launches == null || widgetId == null || !launches.has(widgetId)) {
            return;
        }
        launches.remove(widgetId);
        try {
            preferences.edit().putString(KEY_LAUNCHES, root.toString()).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** Forget every tile that hosted the explicitly closed package in the current session. */
    public static void forgetPackage(
            Context context, SharedPreferences preferences, String packageName) {
        forgetPackage(preferences, sessionId(context, preferences), packageName);
    }

    public static void forgetPackage(
            SharedPreferences preferences, String sessionId, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        JSONObject root = readSession(preferences, sessionId);
        JSONObject launches = root.optJSONObject(KEY_LAUNCH_ITEMS);
        if (launches == null) {
            return;
        }
        boolean changed = false;
        Iterator<String> widgetIds = launches.keys();
        while (widgetIds.hasNext()) {
            JSONObject launch = launches.optJSONObject(widgetIds.next());
            if (launch != null && packageName.equals(launch.optString(KEY_PACKAGE, ""))) {
                widgetIds.remove();
                changed = true;
            }
        }
        if (changed) {
            preferences.edit().putString(KEY_LAUNCHES, root.toString()).apply();
        }
    }

    private static JSONObject readSession(SharedPreferences preferences, String sessionId) {
        JSONObject root = new JSONObject();
        try {
            root = new JSONObject(preferences.getString(KEY_LAUNCHES, "{}"));
        } catch (JSONException ignored) {
        }
        if (!sessionId.equals(root.optString(KEY_SESSION, ""))) {
            root = new JSONObject();
            try {
                root.put(KEY_SESSION, sessionId);
            } catch (JSONException ignored) {
            }
        }
        return root;
    }
}
