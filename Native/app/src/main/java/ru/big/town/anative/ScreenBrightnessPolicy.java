package ru.big.town.anative;

import ru.big.town.common.ScreenBrightnessSettings;

import java.util.List;

/** Retains the last target through sensor outages and uncovered rule intervals. */
final class ScreenBrightnessPolicy {
    static final class Target {
        final int percent;
        final boolean night;

        Target(int percent, boolean night) {
            this.percent = percent;
            this.night = night;
        }
    }

    private Target target = new Target(-1, false);

    Target next(List<ScreenBrightnessSettings.Rule> rules, int sensor, int primary, int nightThreshold) {
        if (nightThreshold < 0 || nightThreshold > 7) {
            throw new IllegalArgumentException("Invalid night threshold");
        }
        ScreenBrightnessSettings.Rule matched = ScreenBrightnessSettings.matchingRule(rules, sensor);
        boolean night = sensor >= 0 && sensor <= 7 ? sensor <= nightThreshold : target.night;
        int percent = target.percent;
        if (percent < 0 && (matched == null || !night)) {
            if (primary >= 1 && primary <= 100) percent = primary;
        } else if (matched != null) {
            percent = matched.percent;
        }
        target = new Target(percent, night);
        return target;
    }
}
