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
            case CarPlayClimateProtocol.CYCLE_RECIRCULATION:
                return value == 1;
            case CarPlayClimateProtocol.VENT_MODE:
                return value == Math.rint(value) && value >= 0 && value <= 4;
            case CarPlayClimateProtocol.POWER:
            case CarPlayClimateProtocol.COMPRESSOR:
            case CarPlayClimateProtocol.AUTO:
            case CarPlayClimateProtocol.SYNC:
            case CarPlayClimateProtocol.RECIRCULATION:
            case CarPlayClimateProtocol.VENT_UPPER:
            case CarPlayClimateProtocol.VENT_MIDDLE:
            case CarPlayClimateProtocol.VENT_LOWER:
                return value == 0 || value == 1;
            default:
                return false;
        }
    }

    static boolean reached(OemClimateState state, String field, double target) {
        if (CarPlayClimateProtocol.RECIRCULATION.equals(field)
                && state.circulation == OemClimateState.CIRCULATION_AUTOMATIC) {
            return false;
        }
        Double observed = state.values().get(field);
        if (observed == null || observed != target) {
            return false;
        }
        if (ClimateAirDistribution.handles(field)) {
            return state.power == 1 && state.automatic == 0;
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
        boolean circulationCommand =
                CarPlayClimateProtocol.RECIRCULATION.equals(field)
                        || CarPlayClimateProtocol.CYCLE_RECIRCULATION.equals(field);
        if (circulationCommand ? !state.hasCirculation() : state.values().get(field) == null) {
            return null;
        }
        if (ClimateAirDistribution.handles(field)) {
            int mode =
                    CarPlayClimateProtocol.VENT_MODE.equals(field)
                            ? (int) target
                            : ClimateAirDistribution.targetMode(
                                    state.airDistribution, field, target == 1);
            return state.power == 1 && mode >= 0
                    ? new ClimateCommand("AC_FLOW_MODE", 13, mode)
                    : null;
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
            case CarPlayClimateProtocol.CYCLE_RECIRCULATION:
                return new ClimateCommand("AC_RECIRC_AIR", 11, 1);
            default:
                return null;
        }
    }

    static boolean circulationAdvanced(OemClimateState before, OemClimateState after) {
        return before.hasCirculation()
                && after.hasCirculation()
                && before.circulation != after.circulation;
    }

    static int nextCirculationMode(OemClimateState state) {
        switch (state.circulation) {
            case OemClimateState.CIRCULATION_INTERNAL:
                return OemClimateState.CIRCULATION_AUTOMATIC;
            case OemClimateState.CIRCULATION_AUTOMATIC:
                return OemClimateState.CIRCULATION_EXTERNAL;
            case OemClimateState.CIRCULATION_EXTERNAL:
                return OemClimateState.CIRCULATION_INTERNAL;
            default:
                return -1;
        }
    }

    private static ClimateCommand manualFan(int level) {
        // Model97 reports levels 1–7, but AC_BLOWER accepts the corresponding commands 2–8.
        return level >= 1 && level <= OemClimateState.MAX_FAN_LEVEL
                ? new ClimateCommand("AC_BLOWER", 1, level + 1)
                : null;
    }
}
