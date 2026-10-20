package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** H97C seat levels are retained while off; the separate switch determines the active level. */
final class OemSeatClimateState {
    enum Channel {
        DRIVER_HEATING(
                CarPlayClimateProtocol.DRIVER_HEATING_LEVEL,
                CarPlayClimateProtocol.DRIVER_HEATING_ON,
                "FRONT_SEAT_HEATING_COMMAND_LEFT",
                261,
                "FRONT_SEAT_HEATING_SWITCH_LEFT",
                698),
        PASSENGER_HEATING(
                CarPlayClimateProtocol.PASSENGER_HEATING_LEVEL,
                CarPlayClimateProtocol.PASSENGER_HEATING_ON,
                "FRONT_SEAT_HEATING_COMMAND_RIGHT",
                260,
                "FRONT_SEAT_HEATING_SWITCH_RIGHT",
                697),
        REAR_LEFT_HEATING(
                CarPlayClimateProtocol.REAR_LEFT_HEATING_LEVEL,
                CarPlayClimateProtocol.REAR_LEFT_HEATING_ON,
                "REAR_SEAT_HEATING_COMMAND_LEFT",
                1030,
                "REAR_SEAT_HEATING_SWITCH_LEFT",
                1028),
        REAR_RIGHT_HEATING(
                CarPlayClimateProtocol.REAR_RIGHT_HEATING_LEVEL,
                CarPlayClimateProtocol.REAR_RIGHT_HEATING_ON,
                "REAR_SEAT_HEATING_COMMAND_RIGHT",
                1029,
                "REAR_SEAT_HEATING_SWITCH_RIGHT",
                1027),
        DRIVER_VENTILATION(
                CarPlayClimateProtocol.DRIVER_VENTILATION_LEVEL,
                CarPlayClimateProtocol.DRIVER_VENTILATION_ON,
                "FRONT_SEAT_VENTILATION_COMMAND_LEFT",
                263,
                "FRONT_SEAT_VENTILATION_SWITCH_LEFT",
                700),
        PASSENGER_VENTILATION(
                CarPlayClimateProtocol.PASSENGER_VENTILATION_LEVEL,
                CarPlayClimateProtocol.PASSENGER_VENTILATION_ON,
                "FRONT_SEAT_VENTILATION_COMMAND_RIGHT",
                262,
                "FRONT_SEAT_VENTILATION_SWITCH_RIGHT",
                699);

        final String levelField;
        final String onField;
        final OemVehicleStateTransport.StateKey level;
        final OemVehicleStateTransport.StateKey power;

        Channel(
                String levelField,
                String onField,
                String levelName,
                int levelId,
                String powerName,
                int powerId) {
            this.levelField = levelField;
            this.onField = onField;
            level = new OemVehicleStateTransport.StateKey(levelName, levelId);
            power = new OemVehicleStateTransport.StateKey(powerName, powerId);
        }
    }

    static final List<OemVehicleStateTransport.StateKey> KEYS;

    static {
        List<OemVehicleStateTransport.StateKey> keys = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            keys.add(channel.power);
            keys.add(channel.level);
        }
        KEYS = Collections.unmodifiableList(keys);
    }

    private final Map<OemVehicleStateTransport.StateKey, Integer> raw;

    OemSeatClimateState(Map<OemVehicleStateTransport.StateKey, Integer> raw) {
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    static boolean valid(String field, double value) {
        Channel channel = channel(field);
        if (channel == null || !Double.isFinite(value) || value != Math.rint(value)) {
            return false;
        }
        return value >= 0 && value <= (field.equals(channel.onField) ? 1 : 3);
    }

    static Channel channel(String field) {
        for (Channel channel : Channel.values()) {
            if (channel.levelField.equals(field) || channel.onField.equals(field)) {
                return channel;
            }
        }
        return null;
    }

    Map<String, Double> values() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Channel channel : Channel.values()) {
            Integer power = raw.get(channel.power);
            if (power == null || (power != 1 && power != 2)) {
                continue;
            }
            values.put(channel.onField, power == 2 ? 1.0 : 0.0);
            Integer level = raw.get(channel.level);
            if (power == 1) {
                values.put(channel.levelField, 0.0);
            } else if (level != null && level >= 1 && level <= 3) {
                values.put(channel.levelField, (double) level);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    boolean reached(String field, double target) {
        Double observed = values().get(field);
        return observed != null && observed == target;
    }

    OemVehicleStateTransport.StateValue command(String field, double target) {
        if (!valid(field, target)) {
            return null;
        }
        Channel channel = channel(field);
        if (!values().containsKey(channel.onField)) {
            return null;
        }
        if (target == 0) {
            return new OemVehicleStateTransport.StateValue(channel.power, 1);
        }
        if (field.equals(channel.onField)) {
            Integer remembered = raw.get(channel.level);
            if (remembered == null || remembered < 1 || remembered > 3) {
                return null;
            }
            return new OemVehicleStateTransport.StateValue(channel.level, remembered);
        }
        // A level command enables the channel in the same OEM frame.
        return new OemVehicleStateTransport.StateValue(channel.level, (int) target);
    }
}
