package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class DriveModeCanPolicyTest {
    @Test
    public void standardModesMapToOemVehicleStates() {
        assertPlan("ECO", 1, 2, 1);
        assertPlan("COMFORT", 2, 2, 2);
        assertPlan("SPORT", 3, 3, 3);
        assertPlan("OUTING", 4, 2, 3);
        assertPlan("SNOW", 6, 2, 2);
    }

    @Test
    public void sportAlsoRequestsOemFcmSportState() {
        Map<DriveModeCanPolicy.VehicleStateKey, Integer> values =
                DriveModeCanPolicy.planFor("SPORT", null).values();

        assertEquals(Integer.valueOf(2),
                values.get(DriveModeCanPolicy.VehicleStateKey.FCM_SW_REQ));
    }

    @Test
    public void nonSportModesDoNotTouchFcmState() {
        for (String mode : new String[]{"ECO", "COMFORT", "OUTING", "SNOW"}) {
            assertFalse(DriveModeCanPolicy.planFor(mode, null).values()
                    .containsKey(DriveModeCanPolicy.VehicleStateKey.FCM_SW_REQ));
        }
    }

    @Test
    public void individualKeepsPersonalSteeringAndAcceleratorSettings() {
        DriveModeCanPolicy.IndividualProfile profile =
                DriveModeCanPolicy.IndividualProfile.validated(2, 3);
        Map<DriveModeCanPolicy.VehicleStateKey, Integer> values =
                DriveModeCanPolicy.planFor("INDIVIDUAL", profile).values();

        assertEquals(Integer.valueOf(5),
                values.get(DriveModeCanPolicy.VehicleStateKey.DRIVING_MODE_SET));
        assertEquals(Integer.valueOf(2),
                values.get(DriveModeCanPolicy.VehicleStateKey.EPS_MODE_SET));
        assertEquals(Integer.valueOf(3),
                values.get(DriveModeCanPolicy.VehicleStateKey.PROP_MODE_SET));
        assertFalse(values.containsKey(DriveModeCanPolicy.VehicleStateKey.FCM_SW_REQ));
    }

    @Test
    public void individualWithoutSafeProfileIsRejected() {
        assertNull(DriveModeCanPolicy.planFor("INDIVIDUAL", null));
        assertNull(DriveModeCanPolicy.IndividualProfile.validated(1, 2));
        assertNull(DriveModeCanPolicy.IndividualProfile.validated(2, 4));
    }

    @Test
    public void supportedModesAreExplicit() {
        for (String mode : new String[]{
                "ECO", "COMFORT", "SPORT", "OUTING", "INDIVIDUAL", "SNOW"}) {
            assertTrue(DriveModeCanPolicy.isSupported(mode));
        }
        assertFalse(DriveModeCanPolicy.isSupported(null));
        assertFalse(DriveModeCanPolicy.isSupported("UNKNOWN"));
        assertNull(DriveModeCanPolicy.planFor("UNKNOWN", null));
    }

    @Test
    public void vehicleStateStableIdsMatchOemContract() {
        assertEquals(545, DriveModeCanPolicy.VehicleStateKey.DRIVING_MODE_SET.stableId);
        assertEquals(722, DriveModeCanPolicy.VehicleStateKey.EPS_MODE_SET.stableId);
        assertEquals(774, DriveModeCanPolicy.VehicleStateKey.FCM_SW_REQ.stableId);
        assertEquals(782, DriveModeCanPolicy.VehicleStateKey.PROP_MODE_SET.stableId);
    }

    private static void assertPlan(
            String mode, int driveMode, int steering, int accelerator) {
        Map<DriveModeCanPolicy.VehicleStateKey, Integer> values =
                DriveModeCanPolicy.planFor(mode, null).values();
        assertEquals(Integer.valueOf(driveMode),
                values.get(DriveModeCanPolicy.VehicleStateKey.DRIVING_MODE_SET));
        assertEquals(Integer.valueOf(steering),
                values.get(DriveModeCanPolicy.VehicleStateKey.EPS_MODE_SET));
        assertEquals(Integer.valueOf(accelerator),
                values.get(DriveModeCanPolicy.VehicleStateKey.PROP_MODE_SET));
    }
}
