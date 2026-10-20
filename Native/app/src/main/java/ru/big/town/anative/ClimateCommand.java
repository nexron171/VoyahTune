package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;

/** Selects one OEM operation from a fresh snapshot; toggles are never sent blindly. */
final class ClimateCommand {
    final String field;
    final int stableId;
    final int value;

    private ClimateCommand(String field, int stableId, int value) {
        this.field = field;
        this.stableId = stableId;
        this.value = value;
    }

    static boolean valid(String field, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        switch (field) {
            case CarPlayClimateProtocol.DRIVER_TEMPERATURE:
            case CarPlayClimateProtocol.PASSENGER_TEMPERATURE:
                return value >= 15.5 && value <= 30.5 && value * 2 == Math.rint(value * 2);
            case CarPlayClimateProtocol.FAN:
                return value >= 0
                        && value <= OemClimateState.MAX_FAN_LEVEL
                        && value == Math.rint(value);
            case CarPlayClimateProtocol.POWER:
            case CarPlayClimateProtocol.COMPRESSOR:
            case CarPlayClimateProtocol.AUTO:
            case CarPlayClimateProtocol.SYNC:
            case CarPlayClimateProtocol.RECIRCULATION:
                return value == 0 || value == 1;
            default:
                return false;
        }
    }

    static boolean reached(OemClimateState state, String field, double target) {
        Double observed = state.values().get(field);
        if (observed == null || observed != target) {
            return false;
        }
        if (!CarPlayClimateProtocol.FAN.equals(field)) {
            return true;
        }
        return target == 0 ? state.power == 0 : state.automatic == 0 && state.power == 1;
    }

    static ClimateCommand from(OemClimateState state, String field, double target) {
        if (!valid(field, target)) {
            return null;
        }
        boolean automaticCirculation =
                CarPlayClimateProtocol.RECIRCULATION.equals(field) && state.circulation == 2;
        if (state.values().get(field) == null && !automaticCirculation) {
            return null;
        }
        switch (field) {
            case CarPlayClimateProtocol.DRIVER_TEMPERATURE:
                return new ClimateCommand("AC_LEFT_TEMP", 3, (int) Math.round(target * 10));
            case CarPlayClimateProtocol.PASSENGER_TEMPERATURE:
                return new ClimateCommand("AC_RIGHT_TEMP", 4, (int) Math.round(target * 10));
            case CarPlayClimateProtocol.POWER:
                return new ClimateCommand("AC_POWER_SWITCH", 8, 1);
            case CarPlayClimateProtocol.COMPRESSOR:
                return new ClimateCommand("AC_SWITCH", 5, 1);
            case CarPlayClimateProtocol.SYNC:
                return new ClimateCommand("AC_DUAL", 12, 1);
            case CarPlayClimateProtocol.AUTO:
                if (target == 1) {
                    return new ClimateCommand("AC_AUTO", 7, 1);
                }
                return manualFan(state.fan);
            case CarPlayClimateProtocol.FAN:
                if (target == 0) {
                    return state.power == 1 ? new ClimateCommand("AC_POWER_SWITCH", 8, 1) : null;
                }
                return manualFan((int) target);
            case CarPlayClimateProtocol.RECIRCULATION:
                return new ClimateCommand("AC_RECIRC_AIR", 11, 1);
            default:
                return null;
        }
    }

    static boolean circulationAdvanced(OemClimateState before, OemClimateState after) {
        return before.circulation >= 0
                && before.circulation <= 2
                && after.circulation >= 0
                && after.circulation <= 2
                && before.circulation != after.circulation;
    }

    private static ClimateCommand manualFan(int level) {
        // Model97 reports levels 1–7, but AC_BLOWER accepts the corresponding commands 2–8.
        return level >= 1 && level <= OemClimateState.MAX_FAN_LEVEL
                ? new ClimateCommand("AC_BLOWER", 1, level + 1)
                : null;
    }
}
