package ru.big.town.anative;

import ru.big.town.common.ScreenBrightnessSettings;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Serial reconciliation with independent readback for each physical screen. */
final class ScreenBrightnessController {
    interface Screens {
        int read(int screen) throws Exception;

        boolean write(int screen, int percent) throws Exception;
    }

    private static final int[] SCREENS = {1, 2, 16};
    private final ScreenBrightnessPolicy policy = new ScreenBrightnessPolicy();

    static final class Result {
        final int target;
        final int confirmed;

        Result(int target, int confirmed) {
            this.target = target;
            this.confirmed = confirmed;
        }
    }

    Result reconcile(
            Screens screens,
            List<ScreenBrightnessSettings.Rule> rules,
            int sensor,
            BooleanSupplier active)
            throws Exception {
        if (!active.getAsBoolean()) return new Result(-1, 0);
        int target = policy.next(rules, sensor, screens.read(1));
        int confirmed = 0;
        if (target < 1) return new Result(target, confirmed);
        for (int screen : SCREENS) {
            if (!active.getAsBoolean()) break;
            try {
                int actual = screens.read(screen);
                if (actual != target && active.getAsBoolean()) screens.write(screen, target);
                if (!active.getAsBoolean()) break;
                if (screens.read(screen) == target) confirmed++;
            } catch (Exception unavailable) {
                // One unavailable display must not prevent the other displays from updating.
            }
        }
        return new Result(target, confirmed);
    }
}
