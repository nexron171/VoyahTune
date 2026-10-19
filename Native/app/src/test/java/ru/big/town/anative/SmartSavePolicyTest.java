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
        assertEquals(ELECTRIC, policy.desiredMode(22, 25, 14, SAVE));
        assertEquals(SAVE, policy.desiredMode(22, 25, 15, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(22, 25, 15, ELECTRIC));
        assertEquals(ELECTRIC, policy.desiredMode(22, 25, 35, ELECTRIC));
        assertEquals(SAVE, policy.desiredMode(22, 25, 35, SAVE));
        assertEquals(SAVE, policy.desiredMode(22, 25, 36, ELECTRIC));
    }

    @Test
    public void chargeLockAndRecoveryIncludeTheirExactThresholdsForEveryTarget() {
        for (int target = 25; target <= 80; target += 5) {
            SmartSavePolicy policy = new SmartSavePolicy(false);
            assertEquals(ELECTRIC, policy.desiredMode(target - 4.9f, target, 0, SAVE));
            assertFalse(policy.isChargingLocked());

            assertEquals(SAVE, policy.desiredMode(target - 5f, target, 0, ELECTRIC));
            assertTrue(policy.isChargingLocked());
            assertEquals(SAVE, policy.desiredMode(target - 2.6f, target, 35, SAVE));
            assertTrue(policy.isChargingLocked());

            assertEquals(SAVE, policy.desiredMode(target - 2.5f, target, 35, SAVE));
            assertFalse(policy.isChargingLocked());
            assertEquals(ELECTRIC, policy.desiredMode(target - 4.9f, target, 0, SAVE));
            assertFalse(policy.isChargingLocked());
            assertEquals(SAVE, policy.desiredMode(target - 5.1f, target, 0, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }
    }

    @Test
    public void recoveredChargeWaitsForThirtyFiveBeforeReturningToSmartMode() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        assertEquals(SAVE, policy.desiredMode(75, 80, 0, ELECTRIC));
        assertTrue(policy.isChargingLocked());

        for (int speed : new int[] {0, 14, 15, 34}) {
            assertEquals(SAVE, policy.desiredMode(77.5f, 80, speed, SAVE));
            assertTrue(policy.isChargingLocked());
        }

        assertEquals(SAVE, policy.desiredMode(77.5f, 80, 35, SAVE));
        assertFalse(policy.isChargingLocked());
        assertEquals(SAVE, policy.desiredMode(77.5f, 80, 15, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(77.5f, 80, 14, SAVE));
        assertEquals(ELECTRIC, policy.desiredMode(77.4f, 80, 0, ELECTRIC));
        assertFalse(policy.isChargingLocked());
    }

    @Test
    public void speedAloneCannotReleaseChargeLock() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        for (int speed : new int[] {35, 36, 80}) {
            assertEquals(SAVE, policy.desiredMode(77.4f, 80, speed, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }

        assertEquals(SAVE, policy.desiredMode(77.5f, 80, 80, SAVE));
        assertFalse(policy.isChargingLocked());
    }

    @Test
    public void reachingFullTargetStillRequiresDrivingSpeedToReleaseChargeLock() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        for (int speed : new int[] {-1, 0, 34}) {
            assertEquals(SAVE, policy.desiredMode(80, 80, speed, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }

        assertEquals(SAVE, policy.desiredMode(80, 80, 35, SAVE));
        assertFalse(policy.isChargingLocked());
    }

    @Test
    public void lockSurvivesControllerRecreationAndFollowsChangedTarget() {
        SmartSavePolicy previous = new SmartSavePolicy(false);
        assertEquals(SAVE, previous.desiredMode(20, 25, 0, ELECTRIC));
        SmartSavePolicy policy = new SmartSavePolicy(previous.isChargingLocked());
        assertEquals(SAVE, policy.desiredMode(22.5f, 25, 0, SAVE));
        assertTrue(policy.isChargingLocked());

        assertEquals(SAVE, policy.desiredMode(22.5f, 30, 35, SAVE));
        assertTrue(policy.isChargingLocked());
        assertEquals(SAVE, policy.desiredMode(27.5f, 30, 35, SAVE));
        assertFalse(policy.isChargingLocked());
        assertEquals(ELECTRIC, policy.desiredMode(27.5f, 30, 0, SAVE));

        assertEquals(SAVE, policy.desiredMode(74.9f, 80, 0, ELECTRIC));
        assertEquals(SAVE, policy.desiredMode(74.9f, 75, 0, SAVE));
        assertTrue(policy.isChargingLocked());
        assertEquals(SAVE, policy.desiredMode(74.9f, 75, 35, SAVE));
        assertFalse(policy.isChargingLocked());
        assertEquals(ELECTRIC, policy.desiredMode(74.9f, 75, 0, SAVE));
    }

    @Test
    public void unavailableMeasurementsDoNotAllowElectricOrReleaseChargeLock() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        for (float charge : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            assertEquals(SAVE, policy.desiredMode(charge, 25, 35, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }
        for (int target : new int[] {-1, 0, 24, 26, 85}) {
            assertEquals(SAVE, policy.desiredMode(100, target, 35, ELECTRIC));
            assertTrue(policy.isChargingLocked());
        }
        assertEquals(SAVE, policy.desiredMode(25, 25, -1, ELECTRIC));
        assertTrue(policy.isChargingLocked());
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
