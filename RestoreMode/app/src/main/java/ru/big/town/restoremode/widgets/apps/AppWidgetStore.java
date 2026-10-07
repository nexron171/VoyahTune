package ru.big.town.restoremode.widgets.apps;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Список настраиваемых виджетов приложений для главного экрана. */
public class AppWidgetStore {
    public static final int MAX_WIDGETS = 20;
    static final int DEFAULT_WIDTH = 2;
    static final int DEFAULT_HEIGHT = 4;
    public static final int DEFAULT_DPI = 0;
    public static final int[] DPI_VALUES = {
        0, 120, 140, 160, 180, 200, 213, 240, 260, 280, 300, 320, 360
    };
    private static final String KEY = "appWidgets";

    public static class Profile {
        public String packageName;
        public int dpi;

        Profile(String packageName, int dpi) {
            this.packageName = packageName;
            this.dpi = normalizeDpi(dpi);
        }
    }

    public static class Entry {
        public final String id;
        public String packageName;
        public int width = DEFAULT_WIDTH;
        public int height = DEFAULT_HEIGHT;
        public int dpi = DEFAULT_DPI;
        public boolean autoStart;
        public int autoStartDelay = 3;
        public int selectedProfile;
        public final List<Profile> profiles = new ArrayList<>();

        Entry(String id, String packageName) {
            this.id = id;
            this.packageName = packageName;
        }

        Entry(
                String id,
                String packageName,
                int width,
                int height,
                int dpi,
                boolean autoStart,
                int autoStartDelay) {
            this(id, packageName);
            this.width = clampWidth(width);
            this.height = clampHeight(height);
            this.dpi = normalizeDpi(dpi);
            this.autoStart = autoStart;
            this.autoStartDelay = Math.max(1, Math.min(60, autoStartDelay));
            this.profiles.add(new Profile(packageName, this.dpi));
        }

        public Profile selected() {
            ensureProfiles();
            selectedProfile = Math.max(0, Math.min(selectedProfile, profiles.size() - 1));
            return profiles.get(selectedProfile);
        }

        /** A transferred app need not be the selected (or even a configured) profile here. */
        public boolean setPackageDpi(String runningPackage, int value) {
            if (runningPackage == null || runningPackage.isEmpty()) {
                return false;
            }
            ensureProfiles();
            for (Profile profile : profiles) {
                if (runningPackage.equals(profile.packageName)) {
                    profile.dpi = normalizeDpi(value);
                    ensureProfiles();
                    return true;
                }
            }
            return false;
        }

        public void ensureProfiles() {
            if (profiles.isEmpty()) {
                profiles.add(new Profile(packageName, dpi));
            }
            selectedProfile = Math.max(0, Math.min(selectedProfile, profiles.size() - 1));
            Profile current = profiles.get(selectedProfile);
            packageName = current.packageName;
            dpi = normalizeDpi(current.dpi);
        }
    }

    public static List<Entry> load(SharedPreferences preferences) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String id = object.optString("id", "");
                String packageName = object.optString("package", "");
                if (!id.isEmpty() && !packageName.isEmpty()) {
                    result.add(
                            new Entry(
                                    id,
                                    packageName,
                                    object.optInt("width", DEFAULT_WIDTH),
                                    object.optInt("height", DEFAULT_HEIGHT),
                                    object.optInt("dpi", DEFAULT_DPI),
                                    object.optBoolean("autoStart", false),
                                    object.optInt("autoStartDelay", 3)));
                    Entry entry = result.get(result.size() - 1);
                    entry.selectedProfile = object.optInt("selectedProfile", 0);
                    JSONArray profiles = object.optJSONArray("apps");
                    if (profiles != null) {
                        entry.profiles.clear();
                        for (int j = 0; j < profiles.length(); j++) {
                            JSONObject profile = profiles.optJSONObject(j);
                            if (profile == null) {
                                continue;
                            }
                            String profilePackage = profile.optString("package", "");
                            if (!profilePackage.isEmpty()) {
                                entry.profiles.add(
                                        new Profile(
                                                profilePackage,
                                                profile.optInt("dpi", DEFAULT_DPI)));
                            }
                        }
                    }
                    entry.ensureProfiles();
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static void save(SharedPreferences preferences, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                entry.ensureProfiles();
                object.put("id", entry.id);
                object.put("package", entry.packageName);
                object.put("width", clampWidth(entry.width));
                object.put("height", clampHeight(entry.height));
                object.put("dpi", normalizeDpi(entry.dpi));
                object.put("autoStart", entry.autoStart);
                object.put("autoStartDelay", Math.max(1, Math.min(60, entry.autoStartDelay)));
                object.put("selectedProfile", entry.selectedProfile);
                JSONArray profiles = new JSONArray();
                for (Profile profile : entry.profiles) {
                    JSONObject value = new JSONObject();
                    value.put("package", profile.packageName);
                    value.put("dpi", normalizeDpi(profile.dpi));
                    profiles.put(value);
                }
                object.put("apps", profiles);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY, array.toString()).apply();
    }

    public static Entry add(SharedPreferences preferences, String packageName) {
        List<Entry> entries = load(preferences);
        if (entries.size() >= MAX_WIDGETS) {
            return null;
        }
        Entry entry = new Entry(UUID.randomUUID().toString(), packageName);
        entries.add(entry);
        save(preferences, entries);
        return entry;
    }

    /** Обозначение виджета по порядку создания: A, B, C… ({@code index} — позиция с нуля). */
    public static String designation(int index) {
        if (index < 0) {
            return "";
        }
        if (index < 26) {
            return String.valueOf((char) ('A' + index));
        }
        return String.valueOf(index + 1);
    }

    /** Обозначение конкретного виджета по его id в готовом списке или "" если он не найден. */
    public static String designation(List<Entry> entries, String id) {
        if (entries == null || id == null) {
            return "";
        }
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).id)) {
                return designation(i);
            }
        }
        return "";
    }

    /** Обозначение конкретного виджета по его id или "" если он не найден. */
    public static String designation(SharedPreferences preferences, String id) {
        return designation(load(preferences), id);
    }

    public static void remove(SharedPreferences preferences, String id) {
        List<Entry> entries = load(preferences);
        entries.removeIf(entry -> entry.id.equals(id));
        save(preferences, entries);
    }

    public static Entry find(SharedPreferences preferences, String id) {
        for (Entry entry : load(preferences)) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public static void update(SharedPreferences preferences, Entry changed) {
        List<Entry> entries = load(preferences);
        for (Entry entry : entries) {
            if (entry.id.equals(changed.id)) {
                changed.ensureProfiles();
                entry.packageName = changed.packageName;
                entry.width = clampWidth(changed.width);
                entry.height = clampHeight(changed.height);
                entry.dpi = normalizeDpi(changed.dpi);
                entry.autoStart = changed.autoStart;
                entry.autoStartDelay = Math.max(1, Math.min(60, changed.autoStartDelay));
                entry.selectedProfile = changed.selectedProfile;
                entry.profiles.clear();
                for (Profile profile : changed.profiles) {
                    entry.profiles.add(new Profile(profile.packageName, profile.dpi));
                }
                break;
            }
        }
        save(preferences, entries);
    }

    public static int clampWidth(int width) {
        return Math.max(1, Math.min(12, width));
    }

    public static int clampHeight(int height) {
        return Math.max(1, Math.min(5, height));
    }

    public static int normalizeDpi(int dpi) {
        for (int value : DPI_VALUES) {
            if (value == dpi) {
                return dpi;
            }
        }
        return DEFAULT_DPI;
    }

    public static void addProfile(Entry entry, String packageName, int dpi) {
        entry.ensureProfiles();
        for (Profile profile : entry.profiles) {
            if (profile.packageName.equals(packageName)) {
                return;
            }
        }
        entry.profiles.add(new Profile(packageName, dpi));
    }

    public static void removeProfile(Entry entry, int index) {
        entry.ensureProfiles();
        if (entry.profiles.size() <= 1 || index < 0 || index >= entry.profiles.size()) {
            return;
        }
        entry.profiles.remove(index);
        if (entry.selectedProfile >= entry.profiles.size()) {
            entry.selectedProfile = entry.profiles.size() - 1;
        }
        entry.ensureProfiles();
    }
}
