package ru.big.town.anative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-free OEM contract for the Android 11/H97C Power Hold request. */
final class PowerHoldPolicy {
    static final String BMS_SOC_DISPLAY = "BMS_SOC_DISPLAY";
    static final int BMS_SOC_DISPLAY_ID = 615;
    static final String SCENE_MODE_EXTENDER_SET = "SCENE_MODE_EXTENDER_SET";
    static final int SCENE_MODE_EXTENDER_SET_ID = 1127;
    static final String POWER_HOLD_MODE_SWITCH = "POWER_HOLD_MODE_SWITCH";
    static final int POWER_HOLD_MODE_SWITCH_ID = 1161;
    static final String POWER_HOLD_MODE_TIME = "POWER_HOLD_MODE_TIME";
    static final int POWER_HOLD_MODE_TIME_ID = 1162;
    static final String POWER_HOLD_MODE_WARNING = "POWER_HOLD_MODE_WARNING";
    static final int POWER_HOLD_MODE_WARNING_ID = 1163;

    static final int PARKING_ORDINAL = 0;
    static final int PARKING_VALUE = 0;
    static final int MINIMUM_SOC_PERCENT = 15;
    static final int PERMANENT_DURATION = 15;
    static final int ENGINE_EXTENDER_ON = 1;
    static final int POWER_HOLD_ON = 1;

    enum Outcome {
        ACCEPTED(1),
        NOT_IN_PARK(2),
        LOW_BATTERY(3),
        STATE_UNAVAILABLE(4),
        TRANSPORT_FAILURE(5);

        final int ipcCode;

        Outcome(int ipcCode) {
            this.ipcCode = ipcCode;
        }

        static Outcome fromIpcCode(int code) {
            for (Outcome outcome : values()) {
                if (outcome.ipcCode == code) return outcome;
            }
            return TRANSPORT_FAILURE;
        }
    }

    private PowerHoldPolicy() {
    }

    static boolean isParking(int ordinal, int value) {
        return ordinal == PARKING_ORDINAL && value == PARKING_VALUE;
    }

    static Outcome validate(int gearOrdinal, int gearValue, int socPercent) {
        if (!isParking(gearOrdinal, gearValue)) return Outcome.NOT_IN_PARK;
        if (socPercent < 0) return Outcome.STATE_UNAVAILABLE;
        if (socPercent < MINIMUM_SOC_PERCENT) return Outcome.LOW_BATTERY;
        return Outcome.ACCEPTED;
    }

    static Map<String, Integer> activationValues() {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        values.put(POWER_HOLD_MODE_TIME, PERMANENT_DURATION);
        values.put(SCENE_MODE_EXTENDER_SET, ENGINE_EXTENDER_ON);
        values.put(POWER_HOLD_MODE_SWITCH, POWER_HOLD_ON);
        return Collections.unmodifiableMap(values);
    }

    static Map<String, Integer> stableIds() {
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        ids.put(BMS_SOC_DISPLAY, BMS_SOC_DISPLAY_ID);
        ids.put(SCENE_MODE_EXTENDER_SET, SCENE_MODE_EXTENDER_SET_ID);
        ids.put(POWER_HOLD_MODE_SWITCH, POWER_HOLD_MODE_SWITCH_ID);
        ids.put(POWER_HOLD_MODE_TIME, POWER_HOLD_MODE_TIME_ID);
        ids.put(POWER_HOLD_MODE_WARNING, POWER_HOLD_MODE_WARNING_ID);
        return Collections.unmodifiableMap(ids);
    }
}
