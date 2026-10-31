package ru.big.town.restoremode.widgets.links;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class LinkShortcutStore {
    private static final String KEY = "linkShortcuts";

    private LinkShortcutStore() {}

    public static final class Entry {
        public final String id;
        public final String name;
        public final String address;
        public final boolean deepLink;
        public final String browserPackage;

        public Entry(String id, String name, String address, boolean deepLink) {
            this(id, name, address, deepLink, "");
        }

        public Entry(
                String id, String name, String address, boolean deepLink, String browserPackage) {
            this.browserPackage = deepLink ? "" : browserPackage.trim();
            this.id = id;
            this.name = name.trim();
            this.address = address.trim();
            this.deepLink = deepLink;
            if (id.isEmpty() || this.name.isEmpty()) {
                throw new IllegalArgumentException("Введите название ярлыка");
            }
            Uri uri = Uri.parse(this.address);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !scheme.matches("[a-zA-Z][a-zA-Z0-9+.-]*")
                    || this.address.matches(".*\\s.*")
                    || "file".equalsIgnoreCase(scheme)
                    || "content".equalsIgnoreCase(scheme)
                    || "javascript".equalsIgnoreCase(scheme)
                    || "data".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Введите полную ссылку со схемой");
            }
            if (!deepLink && !isWebAddress(this.address)) {
                throw new IllegalArgumentException(
                        "Введите ссылку http:// или https:// с адресом сайта");
            }
        }
    }

    public static boolean isWebAddress(String address) {
        if (address == null) {
            return false;
        }
        Uri uri = Uri.parse(address);
        return ("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null
                && !uri.getHost().isEmpty();
    }

    public static List<Entry> load(SharedPreferences preferences) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int index = 0; index < array.length(); index++) {
                try {
                    JSONObject object = array.getJSONObject(index);
                    entries.add(
                            new Entry(
                                    object.getString("id"),
                                    object.getString("name"),
                                    object.getString("address"),
                                    object.getBoolean("deepLink"),
                                    object.optString("browserPackage", "")));
                } catch (JSONException | IllegalArgumentException ignored) {
                    // A damaged entry must not hide the remaining shortcuts.
                }
            }
        } catch (JSONException ignored) {
        }
        return entries;
    }

    public static Entry find(SharedPreferences preferences, String id) {
        for (Entry entry : load(preferences)) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public static void put(SharedPreferences preferences, Entry entry) {
        List<Entry> entries = load(preferences);
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id.equals(entry.id)) {
                entries.set(index, entry);
                save(preferences, entries);
                return;
            }
        }
        entries.add(entry);
        save(preferences, entries);
    }

    public static void remove(SharedPreferences preferences, String id) {
        List<Entry> entries = load(preferences);
        entries.removeIf(entry -> entry.id.equals(id));
        save(preferences, entries);
    }

    private static void save(SharedPreferences preferences, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.name);
                object.put("address", entry.address);
                object.put("deepLink", entry.deepLink);
                object.put("browserPackage", entry.browserPackage);
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
            array.put(object);
        }
        preferences.edit().putString(KEY, array.toString()).apply();
    }
}
