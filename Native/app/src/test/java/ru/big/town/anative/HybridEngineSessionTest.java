package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HybridEngineSessionTest {
    private static final int HYBRID = VehicleRestorePolicy.SOC_REV;
    private static final int FUEL = VehicleRestorePolicy.SOC_SREV;
    private static final int WAIT = -1;
    private static final long NOW = 100_000;

    @Test
    public void capturesChargeAtLowSpeedAndReturnsAtForty() {
        HybridEngineSession session = new HybridEngineSession(-1);
        session.recordCharge(31, NOW);
        for (int speed : new int[] {30, 20, 40, 20}) {
            session.recordSpeed(speed);
            assertEquals(WAIT, session.desiredMode(NOW, HYBRID));
        }
        session.recordSpeed(19);
        assertEquals(FUEL, session.desiredMode(NOW, HYBRID));
        assertEquals(35, session.fuelTargetPercent());
        session.recordCharge(36, NOW + 1);
        for (int speed : new int[] {0, 0, 20, 39}) {
            session.recordSpeed(speed);
            assertEquals(FUEL, session.desiredMode(NOW + 1, FUEL));
            assertEquals(35, session.fuelTargetPercent());
        }
        session.recordSpeed(40);
        assertEquals(HYBRID, session.desiredMode(NOW + 1, FUEL));
        session.recordSpeed(21);
        assertEquals(HYBRID, session.desiredMode(NOW + 1, HYBRID));
        session.recordSpeed(0);
        assertEquals(FUEL, session.desiredMode(NOW + 1, HYBRID));
        assertEquals(40, session.fuelTargetPercent());
    }

    @Test
    public void startsInEitherTriggerZoneAndIgnoresThePreviousTripsMode() {
        HybridEngineSession session = new HybridEngineSession(-1);
        session.recordSpeed(30);
        assertEquals(WAIT, session.desiredMode(NOW, FUEL));
        session.recordSpeed(41);
        assertEquals(HYBRID, session.desiredMode(NOW, FUEL));

        HybridEngineSession reselected = new HybridEngineSession(-1);
        reselected.recordSpeed(0);
        assertEquals(WAIT, reselected.desiredMode(NOW, HYBRID));
        reselected.recordCharge(42, NOW);
        assertEquals(FUEL, reselected.desiredMode(NOW, HYBRID));
        assertEquals(45, reselected.fuelTargetPercent());
    }

    @Test
    public void waitsForValidSpeedAndFreshChargeBeforeSelectingFuel() {
        HybridEngineSession session = new HybridEngineSession(-1);
        session.recordCharge(34, NOW);
        assertEquals(WAIT, session.desiredMode(NOW, HYBRID));
        session.recordSpeed(0);
        assertEquals(WAIT, session.desiredMode(NOW + 90_001, HYBRID));
        for (float charge : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            session.recordCharge(charge, NOW);
            assertEquals(WAIT, session.desiredMode(NOW, HYBRID));
        }
        session.recordCharge(34, NOW);
        assertEquals(FUEL, session.desiredMode(NOW + 90_000, HYBRID));
        session.recordSpeed(40);
        assertEquals(HYBRID, session.desiredMode(NOW + 100_000, FUEL));
    }

    @Test
    public void roundsUpWithinTheOemTargetRange() {
        float[] charges = {0, 24.9f, 25, 30, 30.01f, 31, 35, 78, 80, 81, 100};
        int[] targets = {25, 25, 25, 30, 35, 35, 35, 80, 80, 80, 80};
        for (int index = 0; index < charges.length; index++) {
            HybridEngineSession session = new HybridEngineSession(-1);
            session.recordCharge(charges[index], NOW);
            session.recordSpeed(0);
            assertEquals(FUEL, session.desiredMode(NOW, HYBRID));
            assertEquals(targets[index], session.fuelTargetPercent());
        }
    }

    @Test
    public void reconnectInTheSameTripKeepsTheOriginalTargetUntilForty() {
        HybridEngineSession session = new HybridEngineSession(35);
        assertEquals(WAIT, session.desiredMode(NOW, FUEL));
        session.recordCharge(39, NOW);
        session.recordSpeed(28);
        assertEquals(FUEL, session.desiredMode(NOW, FUEL));
        assertEquals(35, session.fuelTargetPercent());
        session.recordSpeed(40);
        assertEquals(HYBRID, session.desiredMode(NOW, FUEL));
        HybridEngineSession returning = new HybridEngineSession(HybridEngineSession.RETURN_PENDING);
        returning.recordSpeed(28);
        assertEquals(HYBRID, returning.desiredMode(NOW, FUEL));
        assertEquals(-1, returning.fuelTargetPercent());
    }
}
