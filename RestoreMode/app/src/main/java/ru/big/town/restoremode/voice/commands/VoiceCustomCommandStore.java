package ru.big.town.restoremode.voice.commands;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.big.town.common.CustomVoiceCommandProtocol;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.vehicle.steering.SteeringActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class VoiceCustomCommandStore {
    public static final String KEY = "customVoiceCommands";
    public static final int MAX_PHRASE_LENGTH = 160;

    public static final class Entry {
        public String id = UUID.randomUUID().toString();
        public String phrase = "";
        public boolean enabled = true;
        public final List<String> actions = new ArrayList<>();
    }

    private VoiceCustomCommandStore() {}

    public static List<Entry> load(SharedPreferences preferences) {
        return fromJson(preferences.getString(KEY, "[]"));
    }

    public static void save(SharedPreferences preferences, Entry changed) {
        List<Entry> entries = load(preferences);
        boolean replaced = false;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id.equals(changed.id)) {
                entries.set(index, changed);
                replaced = true;
                break;
            }
        }
        if (!replaced) entries.add(changed);
        write(preferences, entries);
    }

    public static void remove(SharedPreferences preferences, String id) {
        List<Entry> entries = load(preferences);
        entries.removeIf(entry -> entry.id.equals(id));
        write(preferences, entries);
    }

    private static void write(SharedPreferences preferences, List<Entry> entries) {
        preferences.edit().putString(KEY, toJson(entries)).apply();
        VoiceCommands.invalidate();
    }

    public static String validate(
            Entry changed, List<Entry> entries, List<VoiceCommandCatalog.Command> catalog) {
        String phrase = VoiceCommandCatalog.normalize(changed.phrase);
        if (phrase.isEmpty()) return "Введите голосовую фразу";
        if (changed.phrase.length() > MAX_PHRASE_LENGTH) return "Фраза слишком длинная";
        if (!phrase.matches("[а-я ]+")) return "Используйте русские слова без цифр";
        for (Entry entry : entries) {
            if (!entry.id.equals(changed.id)
                    && phrase.equals(VoiceCommandCatalog.normalize(entry.phrase))) {
                return "Такая фраза уже сохранена";
            }
        }
        List<VoiceCommandCatalog.Command> ordinary = new ArrayList<>();
        for (VoiceCommandCatalog.Command command : catalog) {
            if (!CustomVoiceCommandProtocol.isAction(command.action)) ordinary.add(command);
        }
        VoiceCommandCatalog.Command existing = VoiceCommandCatalog.match(ordinary, phrase);
        if (existing != null) return "Фраза уже используется: " + existing.title;
        if (changed.actions.isEmpty()) return "Добавьте хотя бы одно действие";
        if (changed.actions.size() > CustomVoiceCommandProtocol.MAX_ACTIONS)
            return "Слишком много действий";
        for (String action : changed.actions) {
            if (!SteeringActions.isSelectable(action)) return "Выберите доступное действие";
        }
        return null;
    }

    static void addToCatalog(
            SharedPreferences preferences, List<VoiceCommandCatalog.Command> catalog) {
        List<Entry> entries = load(preferences);
        for (Entry entry : entries) {
            if (!entry.enabled || validate(entry, entries, catalog) != null) continue;
            List<String> actions = new ArrayList<>();
            for (String action : entry.actions) {
                String resolved = SplitConfigSync.resolveSteerAction(action, preferences);
                if (resolved == null || resolved.isEmpty() || "none".equals(resolved)) {
                    actions.clear();
                    break;
                }
                actions.add(resolved);
            }
            if (actions.isEmpty()) continue;
            VoiceCommandCatalog.add(
                    catalog,
                    CustomVoiceCommandProtocol.action(
                            entry.id, SteeringActionStore.encode(actions)),
                    "Своя команда: «" + entry.phrase + "»",
                    entry.phrase);
        }
    }

    static String toJson(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("phrase", entry.phrase.trim());
                item.put("enabled", entry.enabled);
                item.put("actions", new JSONArray(entry.actions));
                array.put(item);
            }
        } catch (org.json.JSONException exception) {
            throw new IllegalArgumentException("Cannot encode custom voice commands", exception);
        }
        return array.toString();
    }

    static List<Entry> fromJson(String json) {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                Entry entry = new Entry();
                entry.id = item.optString("id", "");
                entry.phrase = item.optString("phrase", "").trim();
                entry.enabled = item.optBoolean("enabled", true);
                if (entry.id.isEmpty()
                        || entry.id.contains(":")
                        || entry.phrase.isEmpty()
                        || entry.phrase.length() > MAX_PHRASE_LENGTH
                        || ids.contains(entry.id)) continue;
                JSONArray actions = item.optJSONArray("actions");
                if (actions == null
                        || actions.length() == 0
                        || actions.length() > CustomVoiceCommandProtocol.MAX_ACTIONS) continue;
                boolean valid = true;
                for (int actionIndex = 0; actionIndex < actions.length(); actionIndex++) {
                    Object action = actions.opt(actionIndex);
                    if (!(action instanceof String)
                            || !SteeringActions.isSelectable((String) action)) {
                        valid = false;
                        break;
                    }
                    entry.actions.add((String) action);
                }
                if (!valid) continue;
                ids.add(entry.id);
                entries.add(entry);
            }
        } catch (org.json.JSONException | NullPointerException ignored) {
            entries.clear();
        }
        return entries;
    }
}
