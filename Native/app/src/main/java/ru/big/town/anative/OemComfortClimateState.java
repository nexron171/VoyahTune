package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Seat and steering-wheel controls use VehicleState independently of the HVAC parcel. */
final class OemComfortClimateState {
    static final OemVehicleStateTransport.StateKey STEERING_WHEEL_HEATING =
            new OemVehicleStateTransport.StateKey("STEER_WHEEL_HEAT_SWITCH", 1098);
    static final List<OemVehicleStateTransport.StateKey> KEYS;

    static {
        List<OemVehicleStateTransport.StateKey> keys = new ArrayList<>(OemSeatClimateState.KEYS);
        keys.add(STEERING_WHEEL_HEATING);
        KEYS = Collections.unmodifiableList(keys);
    }

    private final OemSeatClimateState seats;
    private final Integer steeringWheelHeating;

    OemComfortClimateState(Map<OemVehicleStateTransport.StateKey, Integer> raw) {
        seats = new OemSeatClimateState(raw);
        steeringWheelHeating = raw.get(STEERING_WHEEL_HEATING);
    }

    static boolean handles(String field) {
        return CarPlayClimateProtocol.STEERING_WHEEL_HEATING_LEVEL.equals(field)
                || OemSeatClimateState.channel(field) != null;
    }

    static boolean valid(String field, double value) {
        if (CarPlayClimateProtocol.STEERING_WHEEL_HEATING_LEVEL.equals(field)) {
            return value == 0 || value == 1;
        }
        return OemSeatClimateState.valid(field, value);
    }

    Map<String, Double> values() {
        Map<String, Double> values = new LinkedHashMap<>(seats.values());
        if (steeringWheelAvailable()) {
            values.put(
                    CarPlayClimateProtocol.STEERING_WHEEL_HEATING_LEVEL,
                    steeringWheelHeating == 2 ? 1.0 : 0.0);
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
        if (CarPlayClimateProtocol.STEERING_WHEEL_HEATING_LEVEL.equals(field)) {
            if (!steeringWheelAvailable()) {
                return null;
            }
            return new OemVehicleStateTransport.StateValue(
                    STEERING_WHEEL_HEATING, target == 1 ? 2 : 1);
        }
        return seats.command(field, target);
    }

    private boolean steeringWheelAvailable() {
        // H97C reports 10 on a heating fault; it must not be interpreted as "off".
        return steeringWheelHeating != null
                && (steeringWheelHeating == 1 || steeringWheelHeating == 2);
    }
}
