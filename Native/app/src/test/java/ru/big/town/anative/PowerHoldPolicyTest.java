package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class PowerHoldPolicyTest {
    @Test
    public void validatesExactParkingAndMinimumSoc() {
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED, PowerHoldPolicy.validate(0, 0, 15));
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED, PowerHoldPolicy.validate(0, 0, 100));
        assertEquals(PowerHoldPolicy.Outcome.LOW_BATTERY, PowerHoldPolicy.validate(0, 0, 14));
        assertEquals(PowerHoldPolicy.Outcome.STATE_UNAVAILABLE, PowerHoldPolicy.validate(0, 0, -1));
        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK, PowerHoldPolicy.validate(1, 1, 100));
        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK, PowerHoldPolicy.validate(0, -1, 100));
        assertTrue(PowerHoldPolicy.isParking(0, 0));
        assertFalse(PowerHoldPolicy.isParking(3, 3));
    }

    @Test
    public void activationBundleContainsOnlyStockPowerHoldKeys() {
        Map<String, Integer> values = PowerHoldPolicy.activationValues();
        assertEquals(3, values.size());
        assertEquals(Integer.valueOf(15), values.get("POWER_HOLD_MODE_TIME"));
        assertEquals(Integer.valueOf(0), values.get("SCENE_MODE_EXTENDER_SET"));
        assertEquals(Integer.valueOf(1), values.get("POWER_HOLD_MODE_SWITCH"));
        assertFalse(values.containsKey("MAX_CHARGE_SOC_SET"));
        assertFalse(values.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
        assertFalse(values.containsKey("DRIVING_MODE_SET"));
        assertFalse(values.containsKey("ALL_WINDOW_CONTROL"));
        assertFalse(values.containsKey("AC_ON_OFF"));
    }

    @Test
    public void engineExtenderRequiresExplicitOptIn() {
        Map<String, Integer> values = PowerHoldPolicy.activationValues(true);
        assertEquals(Integer.valueOf(1), values.get("SCENE_MODE_EXTENDER_SET"));
        assertEquals(Integer.valueOf(15), values.get("POWER_HOLD_MODE_TIME"));
        assertEquals(
                Integer.valueOf(0),
                PowerHoldPolicy.activationValues().get("SCENE_MODE_EXTENDER_SET"));
        assertTrue(PowerHoldPolicy.Outcome.ARMED.isAccepted());
        assertTrue(PowerHoldPolicy.Outcome.ACTIVE.isAccepted());
        assertFalse(PowerHoldPolicy.Outcome.RESULT_UNKNOWN.isAccepted());
    }

    @Test
    public void decodesOemOutcomesAndRejectsUnknownCodes() {
        PowerHoldPolicy.Outcome[] expected = {
            PowerHoldPolicy.Outcome.ACCEPTED, PowerHoldPolicy.Outcome.NOT_IN_PARK,
            PowerHoldPolicy.Outcome.LOW_BATTERY, PowerHoldPolicy.Outcome.STATE_UNAVAILABLE,
            PowerHoldPolicy.Outcome.TRANSPORT_FAILURE, PowerHoldPolicy.Outcome.ARMED,
            PowerHoldPolicy.Outcome.ACTIVE, PowerHoldPolicy.Outcome.RESULT_UNKNOWN
        };
        for (int code = 1; code <= 8; code++) {
            assertEquals(expected[code - 1], PowerHoldPolicy.Outcome.fromIpcCode(code));
        }
        for (int code : new int[] {-1, 0, 9, Integer.MAX_VALUE}) {
            assertEquals(
                    PowerHoldPolicy.Outcome.TRANSPORT_FAILURE,
                    PowerHoldPolicy.Outcome.fromIpcCode(code));
        }
    }
}
