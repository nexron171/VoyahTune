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
    @Test public void sequenceSegmentsAreAcceptedInOrderAndOnlyOnce() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("a");
        assertTrue(gate.submit("a", 0));
        assertFalse("duplicate segment", gate.submit("a", 0));
        assertFalse("skipped segment", gate.submit("a", 2));
        assertTrue(gate.submit("a", 1));
        assertFalse(gate.submit("a", 1));
    }
    @Test public void newInvocationResetsTheSequenceIndex() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("old");
        assertTrue(gate.submit("old", 0));
        gate.begin("new");
        assertFalse(gate.active("old"));
        assertFalse("stale segment from the replaced session", gate.submit("old", 1));
        assertTrue(gate.submit("new", 0));
    }
    @Test public void oneCommandPerSessionStaysTheDefault() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("a");
        assertTrue(gate.submit("a"));
        assertFalse(gate.submit("a"));
    }
}
