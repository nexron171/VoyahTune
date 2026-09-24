package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSessionGateTest {
    @Test public void newInvocationInvalidatesAlreadyQueuedCommand() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("old"); assertTrue(gate.submit("old"));
        gate.begin("new");
        assertFalse(gate.active("old"));
        assertTrue(gate.submit("new"));
    }
    @Test public void lateCancellationCannotCancelNewSession() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("old"); gate.begin("new"); gate.cancel("old");
        assertTrue(gate.active("new")); assertTrue(gate.submit("new"));
    }
    @Test public void duplicateFinalRecognitionExecutesOnlyOnce() {
        VoiceSessionGate gate = new VoiceSessionGate();
        assertFalse(gate.submit("a")); gate.begin("a");
        assertTrue(gate.submit("a")); assertFalse(gate.submit("a"));
        gate.cancel("a"); assertFalse(gate.active("a")); assertFalse(gate.submit("a"));
    }
}
