package ru.big.town.anative;

import ru.big.town.common.ScreenBrightnessSettings;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Serial reconciliation with independent readback for each physical screen. */
final class ScreenBrightnessController {
    interface Screens extends ScreenBrightnessModeController.Modes {
        int read(int screen) throws Exception;

        boolean write(int screen, int percent) throws Exception;

        boolean isSynchronizationEnabled() throws Exception;

        void enableSynchronization() throws Exception;
    }

    private static final int[] SCREENS = {1, 2, 16};
    private final ScreenBrightnessPolicy policy = new ScreenBrightnessPolicy();

    static final class Result {
        final int target;
        final boolean night;
        final int confirmed;
        final boolean synchronizationConfirmed;

        Result(
                ScreenBrightnessPolicy.Target target,
                int confirmed,
                boolean synchronizationConfirmed) {
            this.target = target.percent;
            this.night = target.night;
            this.confirmed = confirmed;
            this.synchronizationConfirmed = synchronizationConfirmed;
        }
    }

    Result reconcile(
            Screens screens,
            List<ScreenBrightnessSettings.Rule> rules,
            int sensor,
            BooleanSupplier active)
            throws Exception {
        return reconcile(screens, rules, sensor,
                ScreenBrightnessSettings.nightThreshold(ScreenBrightnessSettings.encode(rules)), active);
    }

    Result reconcile(
            Screens screens,
            List<ScreenBrightnessSettings.Rule> rules,
            int sensor,
            int nightThreshold,
            BooleanSupplier active)
            throws Exception {
        ScreenBrightnessPolicy.Target target = new ScreenBrightnessPolicy.Target(-1, false);
        if (!active.getAsBoolean()) return new Result(target, 0, false);
        boolean synchronizationConfirmed = enableSynchronization(screens, active);
        if (!active.getAsBoolean()) return new Result(target, 0, synchronizationConfirmed);
        int primary = screens.read(1);
        target = policy.next(rules, sensor, primary, nightThreshold);
        int confirmed = 0;
        if (target.percent < 0) return new Result(target, confirmed, synchronizationConfirmed);
        int mode =
                target.night
                        ? ScreenBrightnessModeController.NIGHT
                        : ScreenBrightnessModeController.DAY;
        for (int screen : SCREENS) {
            if (!active.getAsBoolean()) break;
            try {
                if (screens.readMode(screen) != mode && active.getAsBoolean()) {
                    screens.writeMode(screen, mode);
                }
                if (!active.getAsBoolean()) break;
                if (screens.readMode(screen) != mode) continue;
                int actual = screens.read(screen);
                if (actual != target.percent && active.getAsBoolean()) {
                    screens.write(screen, target.percent);
                }
                if (!active.getAsBoolean()) break;
                if (screens.read(screen) == target.percent) confirmed++;
            } catch (Exception unavailable) {
                // One unavailable display must not prevent the other displays from updating.
            }
        }
        return new Result(target, confirmed, synchronizationConfirmed);
    }

    private boolean enableSynchronization(Screens screens, BooleanSupplier active) {
        try {
            if (!active.getAsBoolean()) return false;
            if (!screens.isSynchronizationEnabled()) {
                if (!active.getAsBoolean()) return false;
                screens.enableSynchronization();
            }
            return active.getAsBoolean() && screens.isSynchronizationEnabled();
        } catch (Exception unavailable) {
            // Direct display control remains available when the OEM setting cannot be accessed.
            return false;
        }
    }
}
