package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class VehicleRestorePolicyTest {
    @Test
    public void mapsEnergyAndRecuperationToOemValues() {
        assertEquals(1, VehicleRestorePolicy.requireEnergy("SMART"));
        assertEquals(2, VehicleRestorePolicy.requireEnergy("EV"));
        assertEquals(3, VehicleRestorePolicy.requireEnergy("REV"));
        assertEquals(4, VehicleRestorePolicy.requireEnergy("SREV"));
        assertEquals(2, VehicleRestorePolicy.requireRecycle("LOW"));
        assertEquals(3, VehicleRestorePolicy.requireRecycle("MEDIUM"));
        assertEquals(4, VehicleRestorePolicy.requireRecycle("HIGH"));
    }

    @Test
    public void forceEvOverridesEnergyAndRecuperationStaysInTrailingBundle() {
        Map<String, Integer> primary = new LinkedHashMap<>();
        Map<String, Integer> trailing = new LinkedHashMap<>();
        VehicleRestorePolicy.appendPrimaryTo(primary, true, "SREV", true);
        VehicleRestorePolicy.appendRecuperationTo(trailing, true, "HIGH", "SPORT");

        assertEquals(Integer.valueOf(5), primary.get("IVI_SOC_MODESET"));
        assertFalse(primary.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
        assertEquals(Integer.valueOf(4), trailing.get("HUM_ENERGY_PTREGEN_LEVL"));
        assertFalse(trailing.containsKey("IVI_SOC_MODESET"));
    }

    @Test
    public void disabledOptionalModesDoNotTouchTheirVehicleStates() {
        Map<String, Integer> values = new LinkedHashMap<>();
        VehicleRestorePolicy.appendPrimaryTo(values, false, "UNKNOWN", false);
        VehicleRestorePolicy.appendRecuperationTo(values, false, "UNKNOWN", "COMFORT");

        assertFalse(values.containsKey("IVI_SOC_MODESET"));
        assertFalse(values.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
        assertEquals(2, VehicleRestorePolicy.pedestrianSoundState(false));
        assertEquals(1, VehicleRestorePolicy.pedestrianSoundState(true));
    }

    @Test
    public void snowAlwaysLeavesRecuperationToTheVehicle() {
        Map<String, Integer> values = new LinkedHashMap<>();
        VehicleRestorePolicy.appendRecuperationTo(values, true, "HIGH", "SNOW");

        assertFalse(values.containsKey("HUM_ENERGY_PTREGEN_LEVL"));
        assertFalse(VehicleRestorePolicy.allowsRecuperationRestore("SNOW"));
    }

    @Test
    public void invalidEnabledModeIsRejectedBeforeAnySend() {
        try {
            VehicleRestorePolicy.requireEnergy("UNKNOWN");
            fail("Expected unsupported energy mode to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            VehicleRestorePolicy.requireRecycle("UNKNOWN");
            fail("Expected unsupported recuperation mode to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void stableIdsMatchAndroid11H97cAbi() {
        Map<String, Integer> ids = VehicleRestorePolicy.stableIds();
        assertEquals(Integer.valueOf(957), ids.get("IVI_SOC_MODESET"));
        assertEquals(Integer.valueOf(619), ids.get("HUM_ENERGY_PTREGEN_LEVL"));
        assertEquals(Integer.valueOf(665), ids.get("HUM_VSP_FUNCTION_SW"));
    }
}
