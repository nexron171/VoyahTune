package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmartSavePolicyTest {
    private static final int ELECTRIC = VehicleRestorePolicy.SOC_EV;
    private static final int SAVE = VehicleRestorePolicy.SOC_SREV;

    @Test
    public void speedUsesStrictThresholdsAndHoldsModeInBetween() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        assertEquals(ELECTRIC, policy.desiredMode(20, 25, 14, SAVE));
        assertEquals(SAVE, policy.desiredMode(20, 25, 15, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(20, 25, 15, ELECTRIC));
        assertEquals(ELECTRIC, policy.desiredMode(20, 25, 35, ELECTRIC));
        assertEquals(SAVE, policy.desiredMode(20, 25, 35, SAVE));
        assertEquals(SAVE, policy.desiredMode(20, 25, 36, ELECTRIC));
    }

    @Test
    public void fractionalChargeLocksUntilFullTargetEvenAtAStop() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        assertEquals(ELECTRIC, policy.desiredMode(20, 25, 0, SAVE));
        assertFalse(policy.isChargingLocked());
        assertEquals(SAVE, policy.desiredMode(19.9f, 25, 0, ELECTRIC));
        assertTrue(policy.isChargingLocked());
        for (float charge : new float[] {20, 22, 24.9f}) {
            assertEquals(SAVE, policy.desiredMode(charge, 25, 0, SAVE));
        }
        assertEquals(ELECTRIC, policy.desiredMode(25, 25, 0, SAVE));
        assertFalse(policy.isChargingLocked());
        assertEquals(ELECTRIC, policy.desiredMode(30, 25, 0, SAVE));
    }

    @Test
    public void lockSurvivesControllerRecreationAndFollowsChangedTarget() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        assertEquals(SAVE, policy.desiredMode(24.9f, 25, 0, SAVE));
        assertEquals(SAVE, policy.desiredMode(25, 30, 0, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(30, 30, 0, SAVE));
        assertEquals(SAVE, policy.desiredMode(74.9f, 80, 0, ELECTRIC));
        assertEquals(SAVE, policy.desiredMode(79.9f, 80, 0, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(80, 80, 0, SAVE));
    }

    @Test
    public void unavailableMeasurementsDoNotAllowElectricOrReleaseChargeLock() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        for (float charge : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            assertEquals(SAVE, policy.desiredMode(charge, 25, 0, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }
        for (int target : new int[] {-1, 0, 24, 26, 85}) {
            assertEquals(SAVE, policy.desiredMode(100, target, 0, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }
        assertEquals(SAVE, policy.desiredMode(25, 25, -1, ELECTRIC));
    }

    @Test
    public void explicitSaveWaitsForNewSpeedAboveThirtyFive() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        policy.holdManualSave();
        for (int speed : new int[] {0, 5, 14, 15, 35}) {
            policy.onSpeedChanged(speed);
            assertEquals(SAVE, policy.desiredMode(78, 25, speed, ELECTRIC));
            assertEquals(SAVE, policy.desiredMode(78, 80, speed, SAVE));
            assertTrue(policy.isManualSaveHeld());
        }
        // A stale cached speed in a charge poll cannot release the explicit choice.
        assertEquals(SAVE, policy.desiredMode(78, 25, 40, SAVE));
        assertTrue(policy.isManualSaveHeld());
        policy.onSpeedChanged(36);
        assertFalse(policy.isManualSaveHeld());
        assertEquals(SAVE, policy.desiredMode(78, 25, 36, SAVE));
        policy.onSpeedChanged(14);
        assertEquals(ELECTRIC, policy.desiredMode(78, 25, 14, SAVE));
    }
}
