package ru.big.town.anative;

import android.os.Parcel;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The stable AirCondition parcel prefix used by the installed Qinggan service. */
final class OemClimateState {
    static final int MAX_FAN_LEVEL = 7;
    static final int CIRCULATION_INTERNAL = 0;
    static final int CIRCULATION_EXTERNAL = 1;
    static final int CIRCULATION_AUTOMATIC = 2;

    final int power;
    final int compressor;
    final int fan;
    final int automatic;
    final int dual;
    final int circulation;
    final int airDistribution;
    final float driverTemperature;
    final float passengerTemperature;
    final int outsideTemperature;

    OemClimateState(int[] fields) {
        if (fields.length < 36) {
            throw new IllegalArgumentException("Incomplete climate state");
        }
        power = fields[0];
        compressor = fields[1];
        dual = fields[4];
        airDistribution = fields[8];
        fan = fields[10];
        driverTemperature = Float.intBitsToFloat(fields[11]);
        passengerTemperature = Float.intBitsToFloat(fields[12]);
        circulation = fields[14];
        automatic = fields[22];
        outsideTemperature = fields[35];
    }

    static OemClimateState read(Parcel parcel) {
        int[] fields = new int[36];
        for (int index = 0; index < fields.length; index++) {
            fields[index] =
                    index >= 11 && index <= 13
                            ? Float.floatToIntBits(parcel.readFloat())
                            : parcel.readInt();
        }
        return new OemClimateState(fields);
    }

    Map<String, Double> values() {
        Map<String, Double> values = new LinkedHashMap<>();
        temperature(values, CarPlayClimateProtocol.DRIVER_TEMPERATURE, driverTemperature);
        temperature(values, CarPlayClimateProtocol.PASSENGER_TEMPERATURE, passengerTemperature);
        flag(values, CarPlayClimateProtocol.POWER, power);
        flag(values, CarPlayClimateProtocol.COMPRESSOR, compressor);
        flag(values, CarPlayClimateProtocol.AUTO, automatic);
        if (power == 0) {
            values.put(CarPlayClimateProtocol.FAN, 0.0);
        } else if (power == 1 && fan >= 0 && fan <= MAX_FAN_LEVEL) {
            values.put(CarPlayClimateProtocol.FAN, (double) fan);
        }
        if (dual == 0 || dual == 1) {
            values.put(CarPlayClimateProtocol.SYNC, dual == 0 ? 1.0 : 0.0);
        }
        if (hasCirculation()) {
            // On identifies manual recirculation; AutoMode is independent of the HVAC AUTO flag.
            values.put(
                    CarPlayClimateProtocol.RECIRCULATION,
                    circulation == CIRCULATION_INTERNAL ? 1.0 : 0.0);
            values.put(
                    CarPlayClimateProtocol.RECIRCULATION_AUTO,
                    circulation == CIRCULATION_AUTOMATIC ? 1.0 : 0.0);
        }
        for (String field :
                new String[] {
                    CarPlayClimateProtocol.VENT_MODE,
                    CarPlayClimateProtocol.VENT_UPPER,
                    CarPlayClimateProtocol.VENT_MIDDLE,
                    CarPlayClimateProtocol.VENT_LOWER
                }) {
            Double direction = ClimateAirDistribution.observed(airDistribution, field);
            if (direction != null) {
                values.put(field, direction);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    boolean hasCirculation() {
        return circulation >= CIRCULATION_INTERNAL && circulation <= CIRCULATION_AUTOMATIC;
    }

    private static void flag(Map<String, Double> values, String field, int value) {
        if (value == 0 || value == 1) {
            values.put(field, (double) value);
        }
    }

    private static void temperature(Map<String, Double> values, String field, float value) {
        if (Float.isFinite(value)
                && value >= 15.5f
                && value <= 30.5f
                && value * 2 == Math.round(value * 2)) {
            values.put(field, (double) value);
        }
    }
}
