package ru.big.town.anative;

import android.os.Parcel;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The stable AirCondition parcel prefix used by the installed Qinggan service. */
final class OemClimateState {
    final int power;
    final int compressor;
    final int fan;
    final int automatic;
    final int dual;
    final int circulation;
    final float driverTemperature;
    final float passengerTemperature;
    final int outsideTemperature;

    OemClimateState(int[] fields) {
        if (fields.length < 36) throw new IllegalArgumentException("Incomplete climate state");
        power = fields[0];
        compressor = fields[1];
        dual = fields[4];
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
        if (fan >= 0 && fan <= 8) values.put(CarPlayClimateProtocol.FAN, (double) fan);
        if (dual == 0 || dual == 1) values.put(CarPlayClimateProtocol.SYNC, dual == 0 ? 1.0 : 0.0);
        if (circulation == 0 || circulation == 1) {
            values.put(CarPlayClimateProtocol.RECIRCULATION, circulation == 0 ? 1.0 : 0.0);
        }
        return Collections.unmodifiableMap(values);
    }

    private static void flag(Map<String, Double> values, String field, int value) {
        if (value == 0 || value == 1) values.put(field, (double) value);
    }

    private static void temperature(Map<String, Double> values, String field, float value) {
        if (Float.isFinite(value)
                && value >= 15.5f
                && value <= 30.5f
                && value * 2 == Math.round(value * 2)) values.put(field, (double) value);
    }
}
