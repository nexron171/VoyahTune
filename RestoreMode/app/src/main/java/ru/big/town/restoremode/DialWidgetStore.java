package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Сохранённые карточки набора номера главного экрана. */
public final class DialWidgetStore {
    private static final String KEY = "dialWidgets";
    public static final int MAX_COUNT = 100;

    public static class Entry {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String number = "";
    }

    private DialWidgetStore() { }

    static List<Entry> load(SharedPreferences prefs) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length() && result.size() < MAX_COUNT; i++) {
                JSONObject object = array.getJSONObject(i);
                Entry entry = new Entry();
                String id = object.optString("id", "");
                entry.id = id.isEmpty() ? UUID.randomUUID().toString() : id;
                entry.name = object.optString("name", "");
                entry.number = object.optString("number", "");
                result.add(entry);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static void save(SharedPreferences prefs, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            if (array.length() >= MAX_COUNT) break;
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.name);
                object.put("number", entry.number);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }
}
