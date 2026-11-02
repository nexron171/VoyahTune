package ru.big.town.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-sensor brightness levels, with decoding support for legacy ordered rules. */
public final class ScreenBrightnessSettings {
    public static final String ADAPTIVE = "screenBrightnessAdaptive";
    public static final String NIGHT_THRESHOLD = "screenBrightnessNightThreshold";
    public static final int DEFAULT_NIGHT_THRESHOLD = 1;
    public static final String MANUAL = "ru.big.town.anative.SCREEN_BRIGHTNESS_MANUAL";
    public static final String MANUAL_METHOD = "screenBrightnessManual";
    public static final String LEARN_METHOD = "learnScreenBrightness";
    public static final String ENABLED = "screenBrightnessEnabled";
    public static final String RULES = "screenBrightnessRules";
    public static final String ACTION_CONFIG = "ru.big.town.anative.SCREEN_BRIGHTNESS_CONFIG";
    public static final String ACTION_STATUS = "ru.big.town.anative.SCREEN_BRIGHTNESS_STATUS";
    public static final String ACTION_REQUEST = "ru.big.town.anative.SCREEN_BRIGHTNESS_REQUEST";
    public static final String DEFAULT_RULES =
            "level0,eq,0,20\nlevel1,eq,1,20\nlevel2,eq,2,50\nlevel3,eq,3,80\nlevel4,eq,4,80\nlevel5,eq,5,100\nlevel6,eq,6,100\nlevel7,eq,7,100";
    public static final String[] OPERATORS = {"ge", "gt", "le", "lt", "eq"};
    public static final String[] SYMBOLS = {"≥", ">", "≤", "<", "="};

    private ScreenBrightnessSettings() {}

    public static final class Rule {
        public final String id;
        public final String operator;
        public final int threshold;
        public final int percent;
        public final boolean night;

        public Rule(String id, String operator, int threshold, int percent) {
            this(id, operator, threshold, percent, false);
        }

        public Rule(String id, String operator, int threshold, int percent, boolean night) {
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
            this.night = night;
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
                    return "Датчик " + SYMBOLS[index] + " " + threshold + " → " + percent + "%"
                            + (night ? " · ☾ Ночь" : "");
                }
            }
            throw new IllegalStateException(operator);
        }
    }

    public static Rule matchingRule(List<Rule> rules, int level) {
        if (level < 0 || level > 7) return null;
        for (Rule rule : rules) {
            if (rule.matches(level)) return rule;
        }
        return null;
    }

    public static List<Rule> decode(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Missing brightness rules");
        if (encoded.isEmpty()) return Collections.emptyList();
        List<Rule> rules = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (String line : encoded.split("\n", -1)) {
            String[] fields = line.split(",", -1);
            if ((fields.length != 4 && fields.length != 5) || !identifiers.add(fields[0])) {
                throw new IllegalArgumentException("Invalid brightness rule record");
            }
            if (fields.length == 5 && !fields[4].equals("night")) {
                throw new IllegalArgumentException("Invalid brightness mode");
            }
            rules.add(
                    new Rule(
                            fields[0],
                            fields[1],
                            Integer.parseInt(fields[2]),
                            Integer.parseInt(fields[3]),
                            fields.length == 5));
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
            if (rule.night) result.append(",night");
        }
        String encoded = result.toString();
        decode(encoded);
        return encoded;
    }

    public static List<Rule> levels(String encoded) {
        List<Rule> legacy = decode(encoded);
        List<Rule> result = new ArrayList<>();
        for (int level = 0; level <= 7; level++) {
            Rule matched = matchingRule(legacy, level);
            if (matched != null) result.add(new Rule("level" + level, "eq", level, matched.percent));
        }
        return Collections.unmodifiableList(result);
    }

    public static int nightThreshold(String encoded) {
        int threshold = -1;
        List<Rule> rules = decode(encoded);
        for (int level = 0; level <= 7; level++) {
            Rule matched = matchingRule(rules, level);
            if (matched != null && matched.night) threshold = level;
        }
        return threshold < 0 ? DEFAULT_NIGHT_THRESHOLD : threshold;
    }

    public static String calibrate(String encoded, int sensor, int percent) {
        if (sensor < 0 || sensor > 7 || percent < 1 || percent > 100) {
            throw new IllegalArgumentException("Invalid brightness calibration");
        }
        List<Rule> result = new ArrayList<>(levels(encoded));
        result.removeIf(rule -> rule.threshold == sensor);
        result.add(new Rule("level" + sensor, "eq", sensor, percent));
        result.sort(java.util.Comparator.comparingInt(rule -> rule.threshold));
        return encode(result);
    }

    private static boolean validOperator(String operator) {
        for (String candidate : OPERATORS) {
            if (candidate.equals(operator)) return true;
        }
        return false;
    }
}
