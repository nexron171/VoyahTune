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

    Target next(List<ScreenBrightnessSettings.Rule> rules, int sensor, int primary) {
        ScreenBrightnessSettings.Rule matched =
                ScreenBrightnessSettings.matchingRule(rules, sensor);
        if (target.percent < 0 && (matched == null || !matched.night)) {
            if (primary >= 1 && primary <= 100) {
                target = new Target(primary, false);
            }
            return target;
        }
        if (matched != null) {
            target = new Target(matched.percent, matched.night);
        }
        return target;
    }
}
