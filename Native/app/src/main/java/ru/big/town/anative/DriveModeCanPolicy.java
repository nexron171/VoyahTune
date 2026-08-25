package ru.big.town.anative;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Pure mapping from VoyahTune drive-mode names to the OEM VehicleState bundle. */
final class DriveModeCanPolicy {
    enum VehicleStateKey {
        DRIVING_MODE_SET(545),
        EPS_MODE_SET(722),
        PROP_MODE_SET(782);

        final int stableId;

        VehicleStateKey(int stableId) {
            this.stableId = stableId;
        }
    }

    static final class IndividualProfile {
        final int steering;
        final int accelerator;

        private IndividualProfile(int steering, int accelerator) {
            this.steering = steering;
            this.accelerator = accelerator;
        }

        static IndividualProfile validated(int steering, int accelerator) {
            if ((steering != 2 && steering != 3)
                    || accelerator < 1 || accelerator > 3) {
                return null;
            }
            return new IndividualProfile(steering, accelerator);
        }
    }

    static final class Plan {
        private final EnumMap<VehicleStateKey, Integer> values;

        private Plan(EnumMap<VehicleStateKey, Integer> values) {
            this.values = values;
        }

        Map<VehicleStateKey, Integer> values() {
            return Collections.unmodifiableMap(values);
        }
    }

    private DriveModeCanPolicy() {}

    static boolean isSupported(String mode) {
        return "ECO".equals(mode) || "COMFORT".equals(mode) || "SPORT".equals(mode)
                || "OUTING".equals(mode) || "INDIVIDUAL".equals(mode) || "SNOW".equals(mode);
    }

    static Plan planFor(String mode, IndividualProfile individual) {
        if (mode == null) return null;

        final int driveMode;
        final int steering;
        final int accelerator;
        switch (mode) {
            case "ECO":
                driveMode = 1;
                steering = 2;
                accelerator = 1;
                break;
            case "COMFORT":
                driveMode = 2;
                steering = 2;
                accelerator = 2;
                break;
            case "SPORT":
                driveMode = 3;
                steering = 3;
                accelerator = 3;
                break;
            case "OUTING":
                driveMode = 4;
                steering = 2;
                accelerator = 3;
                break;
            case "INDIVIDUAL":
                if (individual == null) return null;
                driveMode = 5;
                steering = individual.steering;
                accelerator = individual.accelerator;
                break;
            case "SNOW":
                driveMode = 6;
                steering = 2;
                accelerator = 2;
                break;
            default:
                return null;
        }

        EnumMap<VehicleStateKey, Integer> values =
                new EnumMap<>(VehicleStateKey.class);
        values.put(VehicleStateKey.DRIVING_MODE_SET, driveMode);
        values.put(VehicleStateKey.EPS_MODE_SET, steering);
        values.put(VehicleStateKey.PROP_MODE_SET, accelerator);
        return new Plan(values);
    }
}
