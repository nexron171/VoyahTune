package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DoorPauseFadeCursorTest {
    @Test
    public void preservesNominalFadeAndRestoreDeadlines() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(12, 12, 500L, 2_200L);

        assertAction(cursor.actionAt(0L), DoorPauseFadeCursor.Kind.WAIT, 0, 42L);
        assertAction(cursor.actionAt(42L), DoorPauseFadeCursor.Kind.WRITE, 11, 0L);
        cursor.markAttempted(11);
        assertAction(cursor.actionAt(42L), DoorPauseFadeCursor.Kind.WAIT, 0, 41L);

        assertAction(cursor.actionAt(500L), DoorPauseFadeCursor.Kind.WRITE, 0, 0L);
        cursor.markAttempted(0);
        assertAction(cursor.actionAt(500L), DoorPauseFadeCursor.Kind.WAIT, 0, 1_700L);
        assertAction(cursor.actionAt(2_200L), DoorPauseFadeCursor.Kind.RESTORE, 0, 0L);
    }

    @Test
    public void skipsRepeatedRoundedVolumes() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(1, 12, 500L, 2_200L);

        assertAction(cursor.actionAt(0L), DoorPauseFadeCursor.Kind.WAIT, 0, 292L);
        assertAction(cursor.actionAt(292L), DoorPauseFadeCursor.Kind.WRITE, 0, 0L);
        cursor.markAttempted(0);
        assertAction(cursor.actionAt(292L), DoorPauseFadeCursor.Kind.WAIT, 0, 1_908L);
    }

    @Test
    public void lowVolumeSchedulesOnlyDistinctTargets() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(3, 12, 500L, 2_200L);

        assertAction(cursor.actionAt(0L), DoorPauseFadeCursor.Kind.WAIT, 0, 125L);
        assertAction(cursor.actionAt(125L), DoorPauseFadeCursor.Kind.WRITE, 2, 0L);
        cursor.markAttempted(2);
        assertAction(cursor.actionAt(125L), DoorPauseFadeCursor.Kind.WAIT, 0, 167L);
        assertAction(cursor.actionAt(292L), DoorPauseFadeCursor.Kind.WRITE, 1, 0L);
        cursor.markAttempted(1);
        assertAction(cursor.actionAt(292L), DoorPauseFadeCursor.Kind.WAIT, 0, 166L);
        assertAction(cursor.actionAt(458L), DoorPauseFadeCursor.Kind.WRITE, 0, 0L);
    }

    @Test
    public void delayedWorkerJumpsToCurrentLevelWithoutReplayingBacklog() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(12, 12, 500L, 2_200L);

        assertAction(cursor.actionAt(42L), DoorPauseFadeCursor.Kind.WRITE, 11, 0L);
        cursor.markAttempted(11);
        assertAction(cursor.actionAt(400L), DoorPauseFadeCursor.Kind.WRITE, 3, 0L);
        cursor.markAttempted(3);
        assertAction(cursor.actionAt(600L), DoorPauseFadeCursor.Kind.WRITE, 0, 0L);
        cursor.markAttempted(0);
        assertAction(cursor.actionAt(600L), DoorPauseFadeCursor.Kind.WAIT, 0, 1_600L);
    }

    @Test
    public void attemptedWriteDoesNotSpinAtSameDeadline() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(12, 12, 500L, 2_200L);

        DoorPauseFadeCursor.Action write = cursor.actionAt(42L);
        cursor.markAttempted(write.volume);

        assertAction(cursor.actionAt(42L), DoorPauseFadeCursor.Kind.WAIT, 0, 41L);
    }

    @Test
    public void backpressureCannotDelayRestoreDeadline() {
        DoorPauseFadeCursor cursor = new DoorPauseFadeCursor(12, 12, 500L, 2_200L);

        assertAction(cursor.actionAt(2_190L), DoorPauseFadeCursor.Kind.WRITE, 0, 0L);
        assertEquals(10L, cursor.capDelayToRestore(2_190L, 42L));
        assertAction(cursor.actionAt(2_200L), DoorPauseFadeCursor.Kind.RESTORE, 0, 0L);
        assertAction(cursor.actionAt(3_000L), DoorPauseFadeCursor.Kind.RESTORE, 0, 0L);
    }

    private static void assertAction(DoorPauseFadeCursor.Action action,
                                     DoorPauseFadeCursor.Kind kind,
                                     int volume, long delayMs) {
        assertEquals(kind, action.kind);
        assertEquals(volume, action.volume);
        assertEquals(delayMs, action.delayMs);
    }
}
