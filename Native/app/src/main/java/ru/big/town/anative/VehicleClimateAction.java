package ru.big.town.anative;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Validated action payload; malformed requests never reach the OEM transport. */
final class VehicleClimateAction {
    final String field;
    final double value;
    final boolean fragrance;
    final Integer fragranceIntensity;
    final Integer fragranceDuration;

    private VehicleClimateAction(String field, double value, boolean fragrance) {
        this(field, value, fragrance, null, null);
    }

    private VehicleClimateAction(
            String field, double value, boolean fragrance, Integer intensity, Integer duration) {
        this.field = field;
        this.value = value;
        this.fragrance = fragrance;
        this.fragranceIntensity = intensity;
        this.fragranceDuration = duration;
    }

    static boolean handles(String action) {
        return action != null && (action.startsWith("climate:") || action.startsWith("fragrance:"));
    }

    Map<OemVehicleStateTransport.StateKey, Integer> fragranceValues() {
        Map<OemVehicleStateTransport.StateKey, Integer> values = new LinkedHashMap<>();
        values.put(
                new OemVehicleStateTransport.StateKey(
                        FragranceRestorePolicy.TASTE_STATE, FragranceRestorePolicy.TASTE_STATE_ID),
                (int) value);
        if (fragranceIntensity != null) {
            values.put(
                    new OemVehicleStateTransport.StateKey(
                            FragranceRestorePolicy.INTENSITY_STATE,
                            FragranceRestorePolicy.INTENSITY_STATE_ID),
                    fragranceIntensity);
        }
        values.put(
                new OemVehicleStateTransport.StateKey(
                        FragranceRestorePolicy.SWITCH_STATE,
                        FragranceRestorePolicy.SWITCH_STATE_ID),
                FragranceRestorePolicy.SWITCH_ON);
        return values;
    }

    int applyClimate(ToIntFunction<String> operation) {
        if (!field.equals("temperature")) {
            return operation.applyAsInt(field);
        }
        int result = operation.applyAsInt(CarPlayClimateProtocol.DRIVER_TEMPERATURE);
        return result == CarPlayClimateProtocol.SUCCESS
                ? operation.applyAsInt(CarPlayClimateProtocol.PASSENGER_TEMPERATURE)
                : result;
    }

    static VehicleClimateAction parse(String action) {
        if (!handles(action)) {
            return null;
        }
        String[] parts = action.split(":", -1);
        try {
            if (parts[0].equals("fragrance")) {
                return parseFragrance(parts);
            }
            if (parts.length == 3 && parts[0].equals("climate")) {
                double value = Double.parseDouble(parts[2]);
                return ClimateCommand.valid(
                                parts[1].equals("temperature")
                                        ? CarPlayClimateProtocol.DRIVER_TEMPERATURE
                                        : parts[1],
                                value)
                        ? new VehicleClimateAction(parts[1], value, false)
                        : null;
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return null;
    }

    private static VehicleClimateAction parseFragrance(String[] parts) {
        if (parts.length != 2 && parts.length != 4 && parts.length != 6) return null;
        int taste = Integer.parseInt(parts[1]);
        if (taste < 1 || taste > 3) return null;
        Integer intensity = null;
        Integer duration = null;
        for (int index = 2; index < parts.length; index += 2) {
            int parameter = Integer.parseInt(parts[index + 1]);
            if (parts[index].equals("intensity")
                    && intensity == null
                    && parameter >= 1
                    && parameter <= 3) {
                intensity = parameter;
            } else if (parts[index].equals("duration")
                    && duration == null
                    && parameter >= 0
                    && parameter <= 2) {
                duration = parameter;
            } else {
                return null;
            }
        }
        return new VehicleClimateAction(
                FragranceRestorePolicy.TASTE_STATE, taste, true, intensity, duration);
    }

    OemVehicleStateTransport.StateValue fragranceTimer() {
        return fragranceDuration == null
                ? null
                : new OemVehicleStateTransport.StateValue(
                        new OemVehicleStateTransport.StateKey(
                                FragranceRestorePolicy.DURATION_STATE,
                                FragranceRestorePolicy.DURATION_STATE_ID),
                        fragranceDuration);
    }
}
