package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.HeadlightWidgetProtocol;

public class HeadlightWidgetStateTest {
    @Test
    public void oemSwitchPositionMapsToAllFourExplicitCommands() {
        String[] commands = {
            "headlights:auto", "headlights:on", "headlights:parking", "headlights:off"
        };
        for (int feedback = 0; feedback < commands.length; feedback++) {
            int mode = HeadlightWidgetState.modeFromFeedback(feedback);
            assertEquals(commands[feedback], HeadlightWidgetProtocol.action(mode));
            assertNotNull(HeadlightCanPolicy.commandForAction(commands[feedback]));
        }
        for (int invalid : new int[] {-1, 4, 255}) {
            assertEquals(-1, HeadlightWidgetState.modeFromFeedback(invalid));
        }
    }

    @Test
    public void acceptedCommandWaitsForFeedbackWithoutChangingSelectedMode() {
        HeadlightWidgetState state = new HeadlightWidgetState();
        assertFalse(state.select(HeadlightWidgetProtocol.LOW_BEAM));
        state.observed(3);
        assertTrue(state.select(HeadlightWidgetProtocol.PARKING));
        assertFalse(state.select(HeadlightWidgetProtocol.AUTO));
        state.sent(true);
        assertEquals(HeadlightWidgetProtocol.OFF, state.mode);
        assertEquals(HeadlightWidgetProtocol.PARKING, state.pending);
        assertFalse(state.available());
        state.observed(2);
        assertEquals(HeadlightWidgetProtocol.PARKING, state.mode);
        assertEquals(-1, state.pending);
        assertTrue(state.available());
    }

    @Test
    public void feedbackBeforeSendCompletionDoesNotUnlockTheNextCommandEarly() {
        HeadlightWidgetState state = new HeadlightWidgetState();
        state.observed(3);
        state.select(HeadlightWidgetProtocol.LOW_BEAM);
        state.observed(1);
        assertFalse(state.available());
        state.sent(true);
        assertTrue(state.available());
    }

    @Test
    public void failureTimeoutAndDisconnectNeverFabricateASelection() {
        HeadlightWidgetState state = new HeadlightWidgetState();
        state.observed(3);
        state.select(HeadlightWidgetProtocol.AUTO);
        state.sent(false);
        assertEquals(HeadlightWidgetProtocol.OFF, state.mode);
        assertTrue(state.available());
        state.select(HeadlightWidgetProtocol.PARKING);
        state.sent(true);
        state.timeout();
        assertEquals(HeadlightWidgetProtocol.OFF, state.mode);
        assertEquals("Режим не подтверждён", state.message);
        state.reset("Нет связи");
        assertEquals(-1, state.mode);
        assertFalse(state.available());
    }

    @Test
    public void manualSelectionsSuspendAutomationAndAutoReleasesIt() {
        assertFalse(HeadlightSelectionController.suspendsAutomatic(-1));
        for (int mode : new int[] {0, 1, 2}) {
            assertTrue(HeadlightSelectionController.suspendsAutomatic(mode));
        }
        assertFalse(HeadlightSelectionController.suspendsAutomatic(HeadlightWidgetProtocol.AUTO));
    }

    @Test
    public void wakeRestoreIsCancelledBySleepOrAnyNewerSelectionIncludingTheSameMode() {
        assertTrue(HeadlightSelectionController.restoreIsCurrent(1, 1, 7, 7, true, false));
        assertFalse(HeadlightSelectionController.restoreIsCurrent(1, 1, 7, 7, false, false));
        assertFalse(HeadlightSelectionController.restoreIsCurrent(1, 1, 7, 8, true, false));
        assertFalse(HeadlightSelectionController.restoreIsCurrent(1, 2, 7, 7, true, false));
        assertFalse(HeadlightSelectionController.restoreIsCurrent(3, 3, 7, 7, true, true));
        assertTrue(HeadlightSelectionController.restoreIsCurrent(3, 3, 7, 7, true, false));
        assertFalse(HeadlightSelectionController.restoreIsCurrent(-1, -1, 7, 7, true, false));
    }
}
