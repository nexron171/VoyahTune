package ru.big.town.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered rules shared by the editor and the Native controller. */
public final class ScreenBrightnessSettings {
    public static final String ENABLED = "screenBrightnessEnabled";
    public static final String RULES = "screenBrightnessRules";
    public static final String ACTION_CONFIG = "ru.big.town.anative.SCREEN_BRIGHTNESS_CONFIG";
    public static final String ACTION_STATUS = "ru.big.town.anative.SCREEN_BRIGHTNESS_STATUS";
    public static final String ACTION_REQUEST = "ru.big.town.anative.SCREEN_BRIGHTNESS_REQUEST";
    public static final String DEFAULT_RULES =
            "bright,ge,5,100\nmedium,ge,3,80\ndim,ge,2,50\ndark,lt,2,20";
    public static final String[] OPERATORS = {"ge", "gt", "le", "lt", "eq"};
    public static final String[] SYMBOLS = {"≥", ">", "≤", "<", "="};

    private ScreenBrightnessSettings() {}

    public static final class Rule {
        public final String id;
        public final String operator;
        public final int threshold;
        public final int percent;

        public Rule(String id, String operator, int threshold, int percent) {
            if (id == null
                    || !id.matches("[A-Za-z0-9_-]+")
                    || !validOperator(operator)
                    || threshold < 0
                    || threshold > 7
                    || percent < 1
                    || percent > 100) {
                throw new IllegalArgumentException("Invalid screen brightness rule");
            }
            this.id = id;
            this.operator = operator;
            this.threshold = threshold;
            this.percent = percent;
        }

        public boolean matches(int level) {
            switch (operator) {
                case "ge":
                    return level >= threshold;
                case "gt":
                    return level > threshold;
                case "le":
                    return level <= threshold;
                case "lt":
                    return level < threshold;
                default:
                    return level == threshold;
            }
        }

        public String label() {
            for (int index = 0; index < OPERATORS.length; index++) {
                if (operator.equals(OPERATORS[index])) {
                    return "Датчик " + SYMBOLS[index] + " " + threshold + " → " + percent + "%";
                }
            }
            throw new IllegalStateException(operator);
        }
    }

    public static int target(List<Rule> rules, int level) {
        if (level < 0 || level > 7) return -1;
        for (Rule rule : rules) {
            if (rule.matches(level)) return rule.percent;
        }
        return -1;
    }

    public static List<Rule> decode(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Missing brightness rules");
        if (encoded.isEmpty()) return Collections.emptyList();
        List<Rule> rules = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (String line : encoded.split("\n", -1)) {
            String[] fields = line.split(",", -1);
            if (fields.length != 4 || !identifiers.add(fields[0])) {
                throw new IllegalArgumentException("Invalid brightness rule record");
            }
            rules.add(
                    new Rule(
                            fields[0],
                            fields[1],
                            Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3])));
        }
        return Collections.unmodifiableList(rules);
    }

    public static String encode(List<Rule> rules) {
        StringBuilder result = new StringBuilder();
        for (Rule rule : rules) {
            if (result.length() > 0) result.append('\n');
            result.append(rule.id)
                    .append(',')
                    .append(rule.operator)
                    .append(',')
                    .append(rule.threshold)
                    .append(',')
                    .append(rule.percent);
        }
        String encoded = result.toString();
        decode(encoded);
        return encoded;
    }

    private static boolean validOperator(String operator) {
        for (String candidate : OPERATORS) {
            if (candidate.equals(operator)) return true;
        }
        return false;
    }
}
