package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PowerHoldStatusPolicyTest {
    @Test
    public void acceptedRequestNeedsExactSwitchFeedback() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(7);
        machine.onSwitch(7, 0);
        long generation = machine.beginActivation();
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVATING, machine.snapshot().status);

        machine.finishActivation(generation, PowerHoldPolicy.Outcome.ACCEPTED);
        assertEquals(PowerHoldStatusPolicy.Status.REQUESTED, machine.snapshot().status);
        machine.onSwitch(7, 0);
        assertEquals(PowerHoldStatusPolicy.Status.REQUESTED, machine.snapshot().status);

        machine.onSwitch(7, 1);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVE, machine.snapshot().status);
        machine.onSwitch(7, 0);
        assertEquals(PowerHoldStatusPolicy.Status.INACTIVE, machine.snapshot().status);
        assertEquals(PowerHoldStatusPolicy.ExitReason.COMMON, machine.snapshot().exitReason);
    }

    @Test
    public void stockArmingWaitsForLockAndThenExactSwitchFeedback() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(3);
        long generation = machine.beginActivation();
        machine.onSwitch(3, 0);
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.ARMED);
        for (int repeat = 0; repeat < 20; repeat++) machine.onSwitch(3, 0);
        assertEquals(PowerHoldStatusPolicy.Status.ARMED, machine.snapshot().status);
        machine.onSwitch(3, 1);
        machine.onOemStatus(2);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVE, machine.snapshot().status);
    }

    @Test
    public void stockCancellationBeforeCanActivationCannotBeRevivedByLateAck() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(3);
        long generation = machine.beginActivation();
        machine.onOemStatus(2);
        machine.onOemStatus(0);
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.ARMED);
        assertEquals(PowerHoldStatusPolicy.Status.INACTIVE, machine.snapshot().status);
    }

    @Test
    public void missingReplyAndMissingVehicleStateStayUnknown() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(3);
        long generation = machine.beginActivation();
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.RESULT_UNKNOWN);
        assertEquals(PowerHoldStatusPolicy.Status.UNKNOWN, machine.snapshot().status);
        generation = machine.beginActivation();
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.STATE_UNAVAILABLE);
        assertEquals(PowerHoldStatusPolicy.Status.UNKNOWN, machine.snapshot().status);
    }

    @Test
    public void lowBatteryWarningEndsArmingWithoutWaitingForSwitchOne() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(3);
        machine.onOemStatus(2);
        machine.onWarning(3, 1);
        machine.onOemStatus(0);
        machine.onSwitch(3, 0);
        assertEquals(PowerHoldStatusPolicy.Status.INACTIVE, machine.snapshot().status);
        assertEquals(PowerHoldStatusPolicy.ExitReason.LOW_BATTERY, machine.snapshot().exitReason);
    }

    @Test
    public void preconditionFailureRestoresKnownState() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(4);
        machine.onSwitch(4, 0);
        long generation = machine.beginActivation();
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.NOT_IN_PARK);
        assertEquals(PowerHoldStatusPolicy.Status.INACTIVE, machine.snapshot().status);

        generation = machine.beginActivation();
        machine.finishActivation(generation, PowerHoldPolicy.Outcome.TRANSPORT_FAILURE);
        assertEquals(PowerHoldStatusPolicy.Status.FAILED, machine.snapshot().status);
    }

    @Test
    public void warningAnnotatesExitInEitherCallbackOrder() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(5);
        machine.onSwitch(5, 1);
        machine.onWarning(5, 1);
        machine.onSwitch(5, 0);
        assertEquals(PowerHoldStatusPolicy.ExitReason.LOW_BATTERY, machine.snapshot().exitReason);

        machine.onSwitch(5, 1);
        machine.onSwitch(5, 0);
        machine.onWarning(5, 2);
        assertEquals(PowerHoldStatusPolicy.ExitReason.TIME_UP, machine.snapshot().exitReason);
    }

    @Test
    public void staleEpochAndGenerationAreIgnored() {
        PowerHoldStatusPolicy.Machine machine = new PowerHoldStatusPolicy.Machine();
        machine.onConnection(10);
        long first = machine.beginActivation();
        long second = machine.beginActivation();
        machine.finishActivation(first, PowerHoldPolicy.Outcome.ACCEPTED);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVATING, machine.snapshot().status);
        machine.onSwitch(9, 1);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVATING, machine.snapshot().status);
        machine.finishActivation(second, PowerHoldPolicy.Outcome.ACCEPTED);
        machine.onSwitch(10, 1);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVE, machine.snapshot().status);
        machine.onConnectionLost(9);
        assertEquals(PowerHoldStatusPolicy.Status.ACTIVE, machine.snapshot().status);
        machine.onConnectionLost(10);
        assertEquals(PowerHoldStatusPolicy.Status.UNKNOWN, machine.snapshot().status);
    }

    @Test
    public void ipcMappingsAreStableAndFailClosed() {
        for (PowerHoldStatusPolicy.Status status : PowerHoldStatusPolicy.Status.values()) {
            assertEquals(status, PowerHoldStatusPolicy.Status.fromIpcCode(status.ipcCode));
        }
        for (PowerHoldStatusPolicy.ExitReason reason : PowerHoldStatusPolicy.ExitReason.values()) {
            assertEquals(reason, PowerHoldStatusPolicy.ExitReason.fromIpcCode(reason.ipcCode));
        }
        assertEquals(
                PowerHoldStatusPolicy.Status.UNKNOWN, PowerHoldStatusPolicy.Status.fromIpcCode(99));
        assertEquals(
                PowerHoldStatusPolicy.ExitReason.NONE,
                PowerHoldStatusPolicy.ExitReason.fromIpcCode(99));
    }
}
