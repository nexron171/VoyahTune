package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoorPauseRunStateTest {

    @Test
    public void duplicateDoesNotReplaceCapturedVolume() {
        DoorPauseRunState state = new DoorPauseRunState();
        int generation = state.begin(17);

        assertTrue(state.isBusy());
        assertEquals(DoorPauseRunState.REJECTED_GENERATION, state.begin(2));
        assertEquals(17, state.finishAndTakeRestoreVolume(generation));
        assertFalse(state.isBusy());
    }

    @Test
    public void zeroVolumeRunStillRemainsBusyUntilFinished() {
        DoorPauseRunState state = new DoorPauseRunState();
        int generation = state.begin(0);

        assertEquals(DoorPauseRunState.REJECTED_GENERATION, state.begin(0));
        assertTrue(state.isCurrent(generation));
        assertEquals(0, state.finishAndTakeRestoreVolume(generation));
    }

    @Test
    public void cancelReturnsRestoreOnceAndInvalidatesQueuedGeneration() {
        DoorPauseRunState state = new DoorPauseRunState();
        int generation = state.begin(11);

        assertEquals(11, state.cancelAndTakeRestoreVolume());
        assertFalse(state.isCurrent(generation));
        assertEquals(DoorPauseRunState.NO_RESTORE_VOLUME,
                state.cancelAndTakeRestoreVolume());
        assertEquals(DoorPauseRunState.NO_RESTORE_VOLUME,
                state.finishAndTakeRestoreVolume(generation));
    }
}
