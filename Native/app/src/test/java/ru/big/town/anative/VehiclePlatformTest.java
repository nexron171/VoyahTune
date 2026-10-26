package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.VehiclePlatform;

public class VehiclePlatformTest {
    @Test
    public void concreteNvramTypeWinsOverGenericProductBranding() {
        assertEquals(VehiclePlatform.H97X, VehiclePlatform.detect("13402", "f6802h", ""));
        assertEquals(VehiclePlatform.H97Y, VehiclePlatform.detect("13401", "f6802h", "f6811"));
        assertEquals(VehiclePlatform.OTHER, VehiclePlatform.detect("143", "f6811", ""));
    }

    @Test
    public void genericTypeUsesTheSameProductRemappingAsTheOem() {
        for (String type : new String[] {"134", "type=134", "0", "", null}) {
            assertEquals(VehiclePlatform.H97X, VehiclePlatform.detect(type, "f6811", ""));
            assertEquals(VehiclePlatform.H97Y, VehiclePlatform.detect(type, "f6802h", "f6805h"));
        }
        assertEquals(VehiclePlatform.H97C, VehiclePlatform.detect("134", "", ""));
        assertEquals(VehiclePlatform.H97C, VehiclePlatform.detect("", "f6802h", ""));
        assertEquals(VehiclePlatform.OTHER, VehiclePlatform.detect(null, null, null));
        assertEquals(VehiclePlatform.OTHER, VehiclePlatform.detect("", "not-f6811", ""));
    }

    @Test
    public void onlyAccFirmwareCanReuseAnAccClaimAndIgnoreDoorFeedbackGate() {
        for (VehiclePlatform platform : VehiclePlatform.values()) {
            ModeSyncPolicy policy = new ModeSyncPolicy();
            policy.useAccRestore(platform.isH97XY());
            policy.activateWake();
            boolean restored = policy.reconcileCompletedAcc(2, "submitted");
            policy.onDriverDoorOpened();
            if (platform == VehiclePlatform.H97X || platform == VehiclePlatform.H97Y) {
                assertTrue(restored);
                assertTrue(policy.canRememberSelection());
            } else {
                assertFalse(restored);
                long restore = policy.beginRestore();
                assertTrue(policy.completeRestore(restore));
                assertFalse(policy.canPersist(restore, "energy"));
                policy.onGear(3);
                assertTrue(policy.canPersist(restore, "energy"));
            }
        }
    }
}
