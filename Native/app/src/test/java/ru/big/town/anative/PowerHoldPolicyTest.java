package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class PowerHoldPolicyTest {
    @Test
    public void stableContractMatchesAndroid11H97c() {
        assertEquals(615, PowerHoldPolicy.BMS_SOC_DISPLAY_ID);
        assertEquals(1127, PowerHoldPolicy.SCENE_MODE_EXTENDER_SET_ID);
        assertEquals(1161, PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID);
        assertEquals(1162, PowerHoldPolicy.POWER_HOLD_MODE_TIME_ID);
        assertEquals(1163, PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID);
        assertEquals(15, PowerHoldPolicy.PERMANENT_DURATION);
        assertEquals(1, PowerHoldPolicy.ENGINE_EXTENDER_ON);
        assertEquals(1, PowerHoldPolicy.POWER_HOLD_ON);
    }

    @Test
    public void validatesExactParkingAndMinimumSoc() {
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED,
                PowerHoldPolicy.validate(0, 0, 15));
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED,
                PowerHoldPolicy.validate(0, 0, 100));
        assertEquals(PowerHoldPolicy.Outcome.LOW_BATTERY,
                PowerHoldPolicy.validate(0, 0, 14));
        assertEquals(PowerHoldPolicy.Outcome.STATE_UNAVAILABLE,
                PowerHoldPolicy.validate(0, 0, -1));
        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK,
                PowerHoldPolicy.validate(1, 1, 100));
        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK,
                PowerHoldPolicy.validate(0, -1, 100));
        assertTrue(PowerHoldPolicy.isParking(0, 0));
        assertFalse(PowerHoldPolicy.isParking(3, 3));
    }

    @Test
    public void activationBundleContainsOnlyStockPowerHoldKeys() {
        Map<String, Integer> values = PowerHoldPolicy.activationValues();
        assertEquals(3, values.size());
        assertEquals(Integer.valueOf(15), values.get("POWER_HOLD_MODE_TIME"));
        assertEquals(Integer.valueOf(1), values.get("SCENE_MODE_EXTENDER_SET"));
        assertEquals(Integer.valueOf(1), values.get("POWER_HOLD_MODE_SWITCH"));
        assertFalse(values.containsKey("MAX_CHARGE_SOC_SET"));
        assertFalse(values.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
        assertFalse(values.containsKey("DRIVING_MODE_SET"));
        assertFalse(values.containsKey("ALL_WINDOW_CONTROL"));
        assertFalse(values.containsKey("AC_ON_OFF"));
    }

    @Test
    public void outcomeIpcCodesAreStable() {
        for (PowerHoldPolicy.Outcome outcome : PowerHoldPolicy.Outcome.values()) {
            assertEquals(outcome, PowerHoldPolicy.Outcome.fromIpcCode(outcome.ipcCode));
        }
        assertEquals(PowerHoldPolicy.Outcome.TRANSPORT_FAILURE,
                PowerHoldPolicy.Outcome.fromIpcCode(-1));
    }
}
