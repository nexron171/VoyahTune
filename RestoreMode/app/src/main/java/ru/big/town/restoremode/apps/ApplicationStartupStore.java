package ru.big.town.restoremode.apps;

import android.content.SharedPreferences;
import android.database.MatrixCursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.big.town.common.ApplicationStartupSettings;
import ru.big.town.common.ApplicationStartupSettings.Screen;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.integration.config.SplitConfigSync;

import java.util.ArrayList;
import java.util.List;

public final class ApplicationStartupStore {
    public static final class Entry {
        public final String target;
        public final String label;

        public Entry(String target, String label) {
            this.target = target;
            this.label = label;
        }
    }

    private ApplicationStartupStore() {}

    public static List<Entry> load(SharedPreferences preferences) {
        return load(preferences, Screen.MAIN);
    }

    public static List<Entry> load(SharedPreferences preferences, Screen screen) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(screen.entriesKey, "[]"));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject item = saved.optJSONObject(index);
                if (item == null) continue;
                String target = item.optString("target", "");
                if ((target.startsWith("app:") && target.length() > 4)
                        || (screen == Screen.MAIN
                                && target.startsWith("split:")
                                && target.length() > 6)) {
                    entries.add(new Entry(target, item.optString("label", target)));
                }
            }
        } catch (JSONException ignored) {
            // A malformed snapshot must not launch a partial sequence.
        }
        return entries;
    }

    public static void save(SharedPreferences preferences, List<Entry> entries) {
        save(preferences, entries, Screen.MAIN);
    }

    public static void save(SharedPreferences preferences, List<Entry> entries, Screen screen) {
        JSONArray saved = new JSONArray();
        try {
            for (Entry entry : entries) {
                saved.put(new JSONObject().put("target", entry.target).put("label", entry.label));
            }
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Cannot serialize application startup", exception);
        }
        preferences.edit().putString(screen.entriesKey, saved.toString()).apply();
    }

    public static int delaySeconds(SharedPreferences preferences) {
        return delaySeconds(preferences, Screen.MAIN);
    }

    public static int delaySeconds(SharedPreferences preferences, Screen screen) {
        return Math.max(
                0,
                Math.min(
                        ApplicationStartupSettings.MAX_DELAY_SECONDS,
                        preferences.getInt(
                                screen.delayKey,
                                ApplicationStartupSettings.DEFAULT_DELAY_SECONDS)));
    }

    public static String resolve(Entry entry, SharedPreferences preferences) {
        if (entry.target.startsWith("app:")) return entry.target;
        List<SplitStore.Preset> presets = SplitStore.load(preferences);
        String identifier = entry.target.substring(6);
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).id.equals(identifier) && presets.get(index).ready()) {
                return SplitConfigSync.resolveSteerAction("split:" + index, preferences);
            }
        }
        return "";
    }

    public static MatrixCursor query(SharedPreferences preferences) {
        return query(preferences, Screen.MAIN);
    }

    public static MatrixCursor query(SharedPreferences preferences, Screen screen) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"enabled", "delaySeconds", "action"});
        int enabled = preferences.getBoolean(screen.enabledKey, false) ? 1 : 0;
        int delay = delaySeconds(preferences, screen);
        for (Entry entry : load(preferences, screen)) {
            String action = resolve(entry, preferences);
            if (!action.isEmpty()) cursor.addRow(new Object[] {enabled, delay, action});
        }
        if (cursor.getCount() == 0) cursor.addRow(new Object[] {enabled, delay, ""});
        return cursor;
    }
}
