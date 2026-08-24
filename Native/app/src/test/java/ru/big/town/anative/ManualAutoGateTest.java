package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ManualAutoGateTest {
    @Test
    public void queuedManualCommandInvalidatesOlderAutomaticAction() {
        ManualAutoGate gate = new ManualAutoGate();
        long automatic = gate.beginAutomaticDecision();

        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        assertFalse(gate.isAutomaticActionCurrent(automatic));
        ticket.close();
    }

    @Test
    public void suppressedAutomaticSendRemainsRetryableIfManualCommandFails() {
        ManualAutoGate gate = new ManualAutoGate();
        long automatic = gate.beginAutomaticDecision();
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();
        AtomicReference<ApplyEngine.WakeActionResult> result = new AtomicReference<>();

        ApplyEngine.runWakeActionExactlyOnce("superseded", () -> true,
                () -> gate.isAutomaticActionCurrent(automatic), result::set);
        ticket.close();

        assertEquals(ApplyEngine.WakeActionResult.FAILED, result.get());
        assertFalse(gate.blocksAntiAuto());
    }

    @Test
    public void automaticDecisionIsRejectedUntilEveryManualCommandFinishes() {
        ManualAutoGate gate = new ManualAutoGate();
        ManualAutoGate.Ticket first = gate.reserveManualCommand();
        ManualAutoGate.Ticket second = gate.reserveManualCommand();

        assertEquals(ManualAutoGate.INVALID_AUTOMATIC_TOKEN,
                gate.beginAutomaticDecision());
        first.close();
        assertTrue(gate.blocksAntiAuto());
        assertEquals(ManualAutoGate.INVALID_AUTOMATIC_TOKEN,
                gate.beginAutomaticDecision());
        second.close();

        long automatic = gate.beginAutomaticDecision();
        assertTrue(gate.isAutomaticActionCurrent(automatic));
    }

    @Test
    public void pendingManualStateIsVisibleUntilTicketCloses() {
        ManualAutoGate gate = new ManualAutoGate();
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        assertTrue(gate.hasPendingManualCommands());
        ticket.close();

        assertFalse(gate.hasPendingManualCommands());
    }

    @Test
    public void ticketCloseIsIdempotent() {
        ManualAutoGate gate = new ManualAutoGate();
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        ticket.close();
        ticket.close();

        assertEquals(0, gate.pendingManualCommandsForTest());
    }

    @Test
    public void completedAutoSelectionKeepsAntiAutoBlocked() {
        ManualAutoGate gate = new ManualAutoGate();
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        gate.setSelected(true);
        ticket.close();

        assertTrue(gate.blocksAntiAuto());
    }

    @Test
    public void completedLowBeamSelectionReleasesAntiAutoBlock() {
        ManualAutoGate gate = new ManualAutoGate();
        gate.setSelected(true);
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        gate.setSelected(false);
        ticket.close();

        assertFalse(gate.blocksAntiAuto());
    }

    @Test
    public void failedSelectionCanRestorePreviousState() {
        ManualAutoGate gate = new ManualAutoGate();
        gate.setSelected(true);
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();

        boolean previous = gate.setSelected(false);
        gate.setSelected(previous);
        ticket.close();

        assertTrue(gate.blocksAntiAuto());
    }

    @Test
    public void twoQueuedTogglesStayBlockedAndReturnToInitialSelection() {
        ManualAutoGate gate = new ManualAutoGate();
        ManualAutoGate.Ticket first = gate.reserveManualCommand();
        ManualAutoGate.Ticket second = gate.reserveManualCommand();
        boolean savedLowBeam = false;

        savedLowBeam = !savedLowBeam;
        gate.setSelected(!savedLowBeam);
        first.close();
        assertTrue(gate.blocksAntiAuto());

        savedLowBeam = !savedLowBeam;
        gate.setSelected(!savedLowBeam);
        second.close();

        assertFalse(savedLowBeam);
        assertTrue(gate.blocksAntiAuto());
    }

    @Test
    public void nextSensorDecisionClearsCompletedManualAuto() {
        ManualAutoGate gate = new ManualAutoGate();
        gate.setSelected(true);

        long automatic = gate.beginAutomaticDecision();

        assertTrue(gate.isAutomaticActionCurrent(automatic));
        assertFalse(gate.blocksAntiAuto());
    }

    @Test
    public void queuedAndCompletedManualCommandInvalidatePreCommitWork() {
        ManualAutoGate gate = new ManualAutoGate();
        long beforeManual = gate.currentRevision();

        ManualAutoGate.Ticket ticket = gate.reserveManualCommand();
        assertFalse(gate.isRevisionCurrent(beforeManual));
        long whileQueued = gate.currentRevision();

        ticket.close();

        assertFalse(gate.isRevisionCurrent(whileQueued));
        assertTrue(gate.isRevisionCurrent(gate.currentRevision()));
    }

    @Test
    public void manualCompletionFenceRunsExactlyOnce() {
        ManualAutoGate gate = new ManualAutoGate();
        AtomicInteger completions = new AtomicInteger();
        ManualAutoGate.Ticket ticket = gate.reserveManualCommand(completions::incrementAndGet);

        ticket.close();
        ticket.close();

        assertEquals(1, completions.get());
    }
}
