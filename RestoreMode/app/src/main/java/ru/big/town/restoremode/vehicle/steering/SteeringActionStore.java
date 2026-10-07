package ru.big.town.restoremode.vehicle.steering;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Хранилище упорядоченных действий одного short/long-слота руля.
 *
 * <p>Старое значение было одиночным id. Length-prefixed формат не накладывает ограничений на
 * содержимое id и безопасно проходит через SharedPreferences, Intent и Settings.Global.
 */
public final class SteeringActionStore {
    static final String PREFIX = "steer-actions-v1:";

    private SteeringActionStore() {}

    public static List<String> load(SharedPreferences prefs, String key) {
        return decode(prefs.getString(key, "none"));
    }

    public static void save(SharedPreferences prefs, String key, List<String> actions) {
        prefs.edit().putString(key, encode(actions)).apply();
    }

    public static String encode(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return "none";
        }
        StringBuilder result = new StringBuilder(PREFIX);
        int count = 0;
        for (String action : actions) {
            if (action == null || action.isEmpty() || "none".equals(action)) {
                continue;
            }
            result.append(action.length()).append(':').append(action);
            count++;
        }
        return count == 0 ? "none" : result.toString();
    }

    public static List<String> decode(String stored) {
        if (stored == null || stored.isEmpty() || "none".equals(stored)) {
            return new ArrayList<>();
        }
        if (!stored.startsWith(PREFIX)) {
            return new ArrayList<>(Collections.singletonList(stored));
        }

        ArrayList<String> actions = new ArrayList<>();
        int position = PREFIX.length();
        while (position < stored.length()) {
            int separator = stored.indexOf(':', position);
            if (separator <= position) {
                return new ArrayList<>();
            }
            final int length;
            try {
                length = Integer.parseInt(stored.substring(position, separator));
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
            int start = separator + 1;
            int end = start + length;
            if (length <= 0 || end > stored.length()) {
                return new ArrayList<>();
            }
            String action = stored.substring(start, end);
            if (!"none".equals(action)) {
                actions.add(action);
            }
            position = end;
        }
        return actions;
    }
}
