package ru.big.town.anative;

import ru.big.town.common.ScreenBrightnessSettings;

import java.util.List;

/** Retains the last target through sensor outages and uncovered rule intervals. */
final class ScreenBrightnessPolicy {
    private int target = -1;

    int next(List<ScreenBrightnessSettings.Rule> rules, int sensor, int primary) {
        int matched = ScreenBrightnessSettings.target(rules, sensor);
        if (matched == ScreenBrightnessSettings.NIGHT_TARGET) {
            target = matched;
            return target;
        }
        if (target < 0) {
            if (primary >= 1 && primary <= 100) target = primary;
            return target;
        }
        if (matched >= 1) target = matched;
        return target;
    }
}
