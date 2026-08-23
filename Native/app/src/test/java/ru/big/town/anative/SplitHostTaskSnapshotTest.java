package ru.big.town.anative;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SplitHostTaskSnapshotTest {
    @Test
    public void unavailableQueryFailsOpen() {
        assertTrue(SplitHostTaskSnapshot.unknown().isAlive("music.app", 17));
    }

    @Test
    public void knownSnapshotRequiresPackageOnRequestedDisplay() {
        SplitHostTaskSnapshot snapshot = SplitHostTaskSnapshot.known(Arrays.asList(
                new SplitHostTaskSnapshot.TaskRecord(1, "music.app", 17),
                new SplitHostTaskSnapshot.TaskRecord(2, "maps.app", 18)));

        assertTrue(snapshot.isAlive("music.app", 17));
        assertFalse(snapshot.isAlive("music.app", 18));
        assertFalse(snapshot.isAlive("missing.app", 17));
    }

    @Test
    public void unreadableDisplayForMatchingPackageFailsOpen() {
        SplitHostTaskSnapshot snapshot = SplitHostTaskSnapshot.known(Arrays.asList(
                new SplitHostTaskSnapshot.TaskRecord(1, "music.app", 99),
                new SplitHostTaskSnapshot.TaskRecord(2, "music.app", null)));

        assertTrue(snapshot.isAlive("music.app", 17));
    }

    @Test
    public void knownEmptySnapshotMarksPaneDead() {
        assertFalse(SplitHostTaskSnapshot.known(Collections.emptyList())
                .isAlive("music.app", 17));
    }
}
