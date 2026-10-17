package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SmartSaveSessionTest {
    private static final int ELECTRIC = VehicleRestorePolicy.SOC_EV;
    private static final int SAVE = VehicleRestorePolicy.SOC_SREV;
    private static final int WAIT = SmartSaveSession.WAIT_FOR_DATA;
    private static final long START = 10_000;

    @Test
    public void startupAtTwentyTwoPercentNeverRequestsSaveWhileMeasurementsArrive() {
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] order : orders) {
            SmartSaveSession session = new SmartSaveSession(new SmartSavePolicy(false), START);
            assertEquals(WAIT, session.desiredMode(START, -1));
            for (int index = 0; index < order.length; index++) {
                if (order[index] == 0) session.recordCharge(22, START);
                if (order[index] == 1) session.recordTarget(25);
                if (order[index] == 2) session.recordSpeed(0);
                assertEquals(index == 2 ? ELECTRIC : WAIT, session.desiredMode(START + index, -1));
            }
        }
    }

    @Test
    public void startupDeadlineIsBoundedAndLateMeasurementsRecover() {
        SmartSaveSession session = new SmartSaveSession(new SmartSavePolicy(false), START);
        assertEquals(START + 5_000, session.nextEvaluationAt(START));
        assertEquals(WAIT, session.desiredMode(START + 4_999, ELECTRIC));
        assertEquals(SAVE, session.desiredMode(START + 5_000, ELECTRIC));
        assertEquals(-1, session.nextEvaluationAt(START + 5_000));
        session.recordCharge(22, START + 6_000);
        session.recordTarget(25);
        session.recordSpeed(0);
        assertEquals(ELECTRIC, session.desiredMode(START + 6_000, SAVE));
    }

    @Test
    public void invalidReceivedDataEndsStartupWaitImmediately() {
        SmartSaveSession session = new SmartSaveSession(new SmartSavePolicy(false), START);
        session.recordTarget(-1);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        session.recordTarget(25);
        assertEquals(SAVE, session.desiredMode(START + 1, ELECTRIC));
        session.recordCharge(22, START);
        session.recordSpeed(0);
        assertEquals(ELECTRIC, session.desiredMode(START + 2, SAVE));
        session.recordCharge(Float.NaN, START + 3);
        assertEquals(SAVE, session.desiredMode(START + 3, ELECTRIC));
    }

    @Test
    public void savedLocksTakePriorityOverWaitingAndChargeLockKeepsItsHysteresis() {
        SmartSavePolicy policy = new SmartSavePolicy(true);
        SmartSaveSession session = new SmartSaveSession(policy, START);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        session.recordCharge(22, START);
        session.recordTarget(25);
        session.recordSpeed(0);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        assertTrue(policy.isChargingLocked());
        session.recordCharge(25, START + 1);
        assertEquals(ELECTRIC, session.desiredMode(START + 1, SAVE));
        assertFalse(policy.isChargingLocked());
        session.recordCharge(19.9f, START + 2);
        assertEquals(SAVE, session.desiredMode(START + 2, ELECTRIC));
        assertTrue(policy.isChargingLocked());
    }

    @Test
    public void manualSaveCanOnlyBeReleasedByNewSpeedAboveThirtyFive() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        policy.holdManualSave();
        SmartSaveSession session = new SmartSaveSession(policy, START);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        session.recordCharge(22, START);
        session.recordTarget(25);
        session.recordSpeed(0);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        session.recordSpeed(36);
        assertEquals(SAVE, session.desiredMode(START, ELECTRIC));
        session.recordSpeed(14);
        assertEquals(ELECTRIC, session.desiredMode(START, SAVE));
    }

    @Test
    public void minuteRefreshKeepsFreshChargeButDoesNotExtendItsExpiry() {
        SmartSaveSession session = new SmartSaveSession(new SmartSavePolicy(false), START);
        session.recordCharge(22, START);
        session.recordTarget(25);
        session.recordSpeed(0);
        assertEquals(ELECTRIC, session.desiredMode(START, ELECTRIC));
        session.recordTarget(25);
        assertEquals(ELECTRIC, session.desiredMode(START + 60_000, ELECTRIC));
        assertEquals(START + 90_001, session.nextEvaluationAt(START + 60_000));
        assertEquals(ELECTRIC, session.desiredMode(START + 90_000, ELECTRIC));
        assertEquals(SAVE, session.desiredMode(START + 90_001, ELECTRIC));
        assertEquals("stale charge", session.reason());
        session.recordCharge(22, START + 91_000);
        assertEquals(ELECTRIC, session.desiredMode(START + 91_000, SAVE));
    }

    @Test
    public void reconnectDoesNotReuseOldMeasurementsOrDeadline() {
        SmartSavePolicy policy = new SmartSavePolicy(false);
        SmartSaveSession previous = new SmartSaveSession(policy, START);
        previous.recordCharge(22, START);
        previous.recordTarget(25);
        previous.recordSpeed(0);
        assertEquals(ELECTRIC, previous.desiredMode(START, SAVE));
        SmartSaveSession next = new SmartSaveSession(policy, START + 100_000);
        assertEquals(WAIT, next.desiredMode(START + 100_000, ELECTRIC));
        assertEquals(START + 105_000, next.nextEvaluationAt(START + 100_000));
    }

    @Test
    public void sameConnectionSampleMayPrecedeDeliveryOfTheConnectionBarrier() {
        SmartSaveSession session = new SmartSaveSession(new SmartSavePolicy(false), START);
        session.recordCharge(22, START - 100);
        session.recordTarget(25);
        session.recordSpeed(0);
        assertEquals(ELECTRIC, session.desiredMode(START, SAVE));
    }
}
