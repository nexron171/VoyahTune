package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class VehicleClimateActionTest {
    @Test
    public void fragranceOptionsEncodeOnlyExplicitSettings() {
        OemVehicleStateTransport.StateKey intensityKey =
                new OemVehicleStateTransport.StateKey("IVI_FRAG_CONCERNTION", 777);
        for (int intensity = 1; intensity <= 3; intensity++) {
            VehicleClimateAction strength =
                    VehicleClimateAction.parse("fragrance:2:intensity:" + intensity);
            assertNotNull(strength);
            assertNull(strength.fragranceTimer());
            assertEquals(Integer.valueOf(intensity), strength.fragranceValues().get(intensityKey));
            for (int duration = 0; duration <= 2; duration++) {
                VehicleClimateAction combined =
                        VehicleClimateAction.parse(
                                "fragrance:2:intensity:" + intensity + ":duration:" + duration);
                assertNotNull(combined);
                assertEquals(
                        Integer.valueOf(intensity), combined.fragranceValues().get(intensityKey));
                assertEquals(
                        new OemVehicleStateTransport.StateKey("FCM_DURATION_CONTROL", 1067),
                        combined.fragranceTimer().key);
                assertEquals(duration, combined.fragranceTimer().value);
                assertEquals(3, combined.fragranceValues().size());
            }
        }
        for (int duration = 0; duration <= 2; duration++) {
            VehicleClimateAction timer =
                    VehicleClimateAction.parse("fragrance:3:duration:" + duration);
            assertNotNull(timer);
            assertFalse(timer.fragranceValues().containsKey(intensityKey));
            assertEquals(duration, timer.fragranceTimer().value);
        }
    }

    @Test
    public void malformedFragranceOptionsAreRejectedRatherThanClamped() {
        for (String action :
                new String[] {
                    "fragrance:1:intensity:0",
                    "fragrance:1:intensity:4",
                    "fragrance:1:duration:-1",
                    "fragrance:1:duration:3",
                    "fragrance:1:duration:30",
                    "fragrance:1:intensity:1.5",
                    "fragrance:1:intensity:1:intensity:2",
                    "fragrance:1:duration:0:duration:1",
                    "fragrance:1:unknown:1",
                    "fragrance:1:intensity",
                    "fragrance:1:duration:"
                }) assertNull(action, VehicleClimateAction.parse(action));
    }

    @Test
    public void fragranceSelectionAlsoEnablesWithoutChangingIntensityOrTimer() {
        for (int taste = 1; taste <= 3; taste++) {
            java.util.Map<OemVehicleStateTransport.StateKey, Integer> values =
                    VehicleClimateAction.parse("fragrance:" + taste).fragranceValues();
            assertEquals(2, values.size());
            assertEquals(
                    Integer.valueOf(taste),
                    values.get(new OemVehicleStateTransport.StateKey("IVI_FRAG_TASTE", 775)));
            assertEquals(
                    Integer.valueOf(2),
                    values.get(new OemVehicleStateTransport.StateKey("FCM_SW_REQ", 774)));
        }
    }

    @Test
    public void commonTemperatureAppliesBothZonesAndPreservesFailures() {
        VehicleClimateAction command = VehicleClimateAction.parse("climate:temperature:20");
        assertNotNull(command);
        assertEquals(20, command.value, 0);
        java.util.List<String> calls = new java.util.ArrayList<>();
        assertEquals(
                0,
                command.applyClimate(
                        field -> {
                            calls.add(field);
                            return 0;
                        }));
        assertEquals(java.util.Arrays.asList("driverTemperature", "passengerTemperature"), calls);
        calls.clear();
        assertEquals(
                -6722,
                command.applyClimate(
                        field -> {
                            calls.add(field);
                            return -6722;
                        }));
        assertEquals(java.util.Collections.singletonList("driverTemperature"), calls);
        assertEquals(
                -6727,
                command.applyClimate(field -> field.equals("driverTemperature") ? 0 : -6727));
        assertNull(VehicleClimateAction.parse("climate:temperature:15"));
        assertNull(VehicleClimateAction.parse("climate:temperature:30.6"));
        assertNull(VehicleClimateAction.parse("climate:temperature:NaN"));
    }

    @Test
    public void zoneTemperatureOnlyAppliesRequestedZone() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        VehicleClimateAction command =
                VehicleClimateAction.parse("climate:passengerTemperature:22");
        assertEquals(
                0,
                command.applyClimate(
                        field -> {
                            calls.add(field);
                            return 0;
                        }));
        assertEquals(java.util.Collections.singletonList("passengerTemperature"), calls);
    }

    @Test
    public void parsesSupportedTargetsWithoutChangingValues() {
        VehicleClimateAction low = VehicleClimateAction.parse("climate:driverTemperature:15.5");
        assertNotNull(low);
        assertEquals("driverTemperature", low.field);
        assertEquals(15.5, low.value, 0);
        assertFalse(low.fragrance);
        assertEquals(
                30.5, VehicleClimateAction.parse("climate:passengerTemperature:30.5").value, 0);
        assertEquals(0, VehicleClimateAction.parse("climate:fan:0").value, 0);
        assertEquals(7, VehicleClimateAction.parse("climate:fan:7").value, 0);
        for (int taste = 1; taste <= 3; taste++) {
            VehicleClimateAction fragrance = VehicleClimateAction.parse("fragrance:" + taste);
            assertNotNull(fragrance);
            assertTrue(fragrance.fragrance);
            assertEquals("IVI_FRAG_TASTE", fragrance.field);
            assertEquals(taste, fragrance.value, 0);
        }
    }

    @Test
    public void rejectsMalformedAndOutOfRangeRequests() {
        for (String action :
                new String[] {
                    null,
                    "",
                    "climate:",
                    "climate:power:2",
                    "climate:fan:8",
                    "climate:fan:1.5",
                    "climate:driverTemperature:15",
                    "climate:driverTemperature:31",
                    "climate:driverTemperature:22.1",
                    "climate:driverTemperature:NaN",
                    "climate:driverTemperature:Infinity",
                    "climate:unknown:1",
                    "climate:power:1:extra",
                    "fragrance:0",
                    "fragrance:4",
                    "fragrance:1.5",
                    "fragrance:one",
                    "fragrance:1:extra"
                }) {
            assertNull(action, VehicleClimateAction.parse(action));
        }
    }
}
