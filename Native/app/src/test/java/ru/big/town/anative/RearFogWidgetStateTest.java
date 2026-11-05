package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class RearFogWidgetStateTest {
    @Test
    public void toggleWaitsForFeedbackAndRejectsRepeatedCommands() {
        RearFogWidgetState state = new RearFogWidgetState();
        assertFalse(state.toggle());
        state.observed(0);
        assertTrue(state.toggle());
        state.sent("");
        assertEquals(0, state.value);
        assertEquals(1, state.pending);
        assertFalse(state.toggle());
        state.observed(1);
        assertTrue(state.available());
        assertEquals(-1, state.pending);
        assertTrue(state.toggle());
        assertEquals(0, state.pending);
    }

    @Test
    public void failuresAndTimeoutNeverInventAnObservedState() {
        RearFogWidgetState state = new RearFogWidgetState();
        state.observed(0);
        state.toggle();
        state.sent("Failed");
        assertEquals(0, state.value);
        assertEquals("Failed", state.message);
        assertTrue(state.toggle());
        state.sent("");
        state.timeout();
        assertEquals(0, state.value);
        assertEquals(-1, state.pending);
        state.observed(null);
        assertFalse(state.available());
        state.observed(2);
        assertFalse(state.toggle());
    }
}
