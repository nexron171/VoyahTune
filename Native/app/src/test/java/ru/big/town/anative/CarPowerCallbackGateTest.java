package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarPowerCallbackGateTest {
    @Test
    public void reconnectRejectsOldListenerCallbacks() {
        CarPowerCallbackGate gate = new CarPowerCallbackGate();
        long first = gate.beginRegistration();
        long second = gate.beginRegistration();

        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void staleFailureCannotInvalidateNewRegistration() {
        CarPowerCallbackGate gate = new CarPowerCallbackGate();
        long first = gate.beginRegistration();
        long second = gate.beginRegistration();

        gate.invalidate(first);

        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void disconnectInvalidatesCurrentListener() {
        CarPowerCallbackGate gate = new CarPowerCallbackGate();
        long generation = gate.beginRegistration();

        gate.invalidateCurrent();

        assertFalse(gate.isCurrent(generation));
    }

    @Test
    public void closeRejectsCallbacksAndFutureRegistration() {
        CarPowerCallbackGate gate = new CarPowerCallbackGate();
        long generation = gate.beginRegistration();

        gate.close();

        assertFalse(gate.isCurrent(generation));
        assertTrue(gate.beginRegistration() == CarPowerCallbackGate.REJECTED_GENERATION);
    }
}
