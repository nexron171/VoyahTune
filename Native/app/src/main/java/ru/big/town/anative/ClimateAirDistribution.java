package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;

/** H97 uses one mode; its window and face outlets are mutually exclusive in the stock UI. */
final class ClimateAirDistribution {
    private static final int UPPER = 1;
    private static final int MIDDLE = 2;
    private static final int LOWER = 4;
    private static final int[] MODES = {MIDDLE, MIDDLE | LOWER, LOWER, UPPER | LOWER, UPPER};

    private ClimateAirDistribution() {}

    static boolean handles(String field) {
        return direction(field) != 0;
    }

    static Double observed(int mode, String field) {
        int direction = direction(field);
        if (mode < 0 || mode >= MODES.length || direction == 0) {
            return null;
        }
        return (MODES[mode] & direction) != 0 ? 1.0 : 0.0;
    }

    static int targetMode(int currentMode, String field, boolean on) {
        int direction = direction(field);
        if (currentMode < 0 || currentMode >= MODES.length || direction == 0) {
            return -1;
        }
        int target = MODES[currentMode];
        if (on) {
            target |= direction;
            if (direction == UPPER) {
                target &= ~MIDDLE;
            } else if (direction == MIDDLE) {
                target &= ~UPPER;
            }
        } else {
            target &= ~direction;
        }
        for (int mode = 0; mode < MODES.length; mode++) {
            if (MODES[mode] == target) {
                return mode;
            }
        }
        // The last open outlet cannot be closed independently of HVAC power.
        return -1;
    }

    private static int direction(String field) {
        switch (field) {
            case CarPlayClimateProtocol.VENT_UPPER:
                return UPPER;
            case CarPlayClimateProtocol.VENT_MIDDLE:
                return MIDDLE;
            case CarPlayClimateProtocol.VENT_LOWER:
                return LOWER;
            default:
                return 0;
        }
    }
}
