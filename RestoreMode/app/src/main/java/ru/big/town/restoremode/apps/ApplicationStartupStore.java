package ru.big.town.restoremode.apps;

import android.content.SharedPreferences;
import android.database.MatrixCursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.big.town.common.ApplicationStartupSettings;
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
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray saved =
                    new JSONArray(preferences.getString(ApplicationStartupSettings.ENTRIES, "[]"));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject item = saved.optJSONObject(index);
                if (item == null) continue;
                String target = item.optString("target", "");
                if ((target.startsWith("app:") && target.length() > 4)
                        || (target.startsWith("split:") && target.length() > 6)) {
                    entries.add(new Entry(target, item.optString("label", target)));
                }
            }
        } catch (JSONException ignored) {
            // A malformed snapshot must not launch a partial sequence.
        }
        return entries;
    }

    public static void save(SharedPreferences preferences, List<Entry> entries) {
        JSONArray saved = new JSONArray();
        try {
            for (Entry entry : entries) {
                saved.put(new JSONObject().put("target", entry.target).put("label", entry.label));
            }
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Cannot serialize application startup", exception);
        }
        preferences.edit().putString(ApplicationStartupSettings.ENTRIES, saved.toString()).apply();
    }

    public static int delaySeconds(SharedPreferences preferences) {
        return Math.max(
                0,
                Math.min(
                        ApplicationStartupSettings.MAX_DELAY_SECONDS,
                        preferences.getInt(
                                ApplicationStartupSettings.DELAY,
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
        MatrixCursor cursor = new MatrixCursor(new String[] {"enabled", "delaySeconds", "action"});
        int enabled = preferences.getBoolean(ApplicationStartupSettings.ENABLED, false) ? 1 : 0;
        int delay = delaySeconds(preferences);
        for (Entry entry : load(preferences)) {
            String action = resolve(entry, preferences);
            if (!action.isEmpty()) cursor.addRow(new Object[] {enabled, delay, action});
        }
        if (cursor.getCount() == 0) cursor.addRow(new Object[] {enabled, delay, ""});
        return cursor;
    }
}
