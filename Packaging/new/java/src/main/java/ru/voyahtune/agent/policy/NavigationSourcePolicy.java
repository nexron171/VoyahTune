package ru.voyahtune.agent.policy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NavigationSourcePolicy {
    private static final Map<String, Integer> YANDEX_ICONS = icons(new Object[][] {
        {"STRAIGHT", 1}, {"SLIGHT_LEFT", 8}, {"SLIGHT_RIGHT", 2}, {"LEFT", 7}, {"RIGHT", 3},
        {"HARD_LEFT", 6}, {"HARD_RIGHT", 4}, {"FORK_LEFT", 8}, {"FORK_RIGHT", 2},
        {"UTURN_LEFT", 5}, {"UTURN_RIGHT", 0}, {"ENTER_ROUNDABOUT", 9}, {"LEAVE_ROUNDABOUT", 9},
        {"EXIT_LEFT", 8}, {"EXIT_RIGHT", 2}
    });
    private static final Map<String, Integer> DGIS_ICONS = icons(new Object[][] {
        {"from", 1}, {"startA", 1}, {"start", 1}, {"crossroad_straight", 1},
        {"crossroad_left", 7}, {"crossroad_right", 3}, {"crossroad_keep_left", 8},
        {"crossroad_keep_right", 2}, {"crossroad_slightly_left", 8}, {"crossroad_slightly_right", 2},
        {"crossroad_sharply_left", 6}, {"crossroad_sharply_right", 4}, {"crossroad_uturn", 5},
        {"turn_over_right_hand", 5}, {"turn_over_left_hand", 0}, {"turn_over", 0},
        {"left_ring_exit", 9}, {"right_ring_exit", 0}, {"ringroad_exit", 0},
        {"ringroad_backward", 9}, {"ringroad_forward", 9}, {"ringroad_left_135", 9},
        {"ringroad_left_180", 9}, {"ringroad_left_45", 9}, {"ringroad_left_90", 9},
        {"ringroad_right_135", 9}, {"ringroad_right_45", 9}, {"ringroad_right_90", 9}
    });
    private static final String SPACE = "[" + TextSeparators.WHITESPACE + "]*";
    private static final Pattern DISTANCE = Pattern.compile(
            "^" + SPACE + "([0-9]+(?:[ \\u00A0\\u202F][0-9]{3})*(?:[.,][0-9]{1,2})?)"
                    + SPACE + "(km|км|m|м)" + SPACE + "$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private NavigationSourcePolicy() {}

    public static int yandexIcon(String action) {
        return YANDEX_ICONS.getOrDefault(action, 0);
    }

    public static int dgisIcon(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".svg")) {
            name = name.substring(0, name.length() - 4);
        }
        return DGIS_ICONS.getOrDefault(name, 0);
    }

    public static boolean driving(String mode) {
        return "car".equals(mode);
    }

    public static int meters(String text) {
        Matcher match = DISTANCE.matcher(text);
        if (!match.matches()) {
            return -1;
        }
        String value = match.group(1).replace(" ", "").replace("\u00A0", "")
                .replace("\u202F", "").replace(',', '.');
        double distance = Double.parseDouble(value);
        if (match.group(2).length() == 2) {
            distance *= 1000;
        }
        return Double.isFinite(distance) && distance <= Integer.MAX_VALUE
                ? (int) Math.round(distance) : -1;
    }

    private static Map<String, Integer> icons(Object[][] entries) {
        Map<String, Integer> values = new HashMap<>();
        for (Object[] entry : entries) {
            values.put((String) entry[0], (Integer) entry[1]);
        }
        return Collections.unmodifiableMap(values);
    }
}
