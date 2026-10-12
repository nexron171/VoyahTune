package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CarPlayTarget;

import java.util.Arrays;
import java.util.Collections;

public class SplitHostTaskSnapshotTest {
    @Test
    public void unavailableQueryFailsOpen() {
        assertTrue(SplitHostTaskSnapshot.unknown().isAlive("music.app", 17));
    }

    @Test
    public void knownSnapshotRequiresPackageOnRequestedDisplay() {
        SplitHostTaskSnapshot snapshot =
                SplitHostTaskSnapshot.known(
                        Arrays.asList(
                                new SplitHostTaskSnapshot.TaskRecord(1, "music.app", 17),
                                new SplitHostTaskSnapshot.TaskRecord(2, "maps.app", 18)));

        assertTrue(snapshot.isAlive("music.app", 17));
        assertFalse(snapshot.isAlive("music.app", 18));
        assertFalse(snapshot.isAlive("missing.app", 17));
    }

    @Test
    public void unreadableDisplayForMatchingPackageFailsOpen() {
        SplitHostTaskSnapshot snapshot =
                SplitHostTaskSnapshot.known(
                        Arrays.asList(
                                new SplitHostTaskSnapshot.TaskRecord(1, "music.app", 99),
                                new SplitHostTaskSnapshot.TaskRecord(2, "music.app", null)));

        assertTrue(snapshot.isAlive("music.app", 17));
    }

    @Test
    public void knownEmptySnapshotMarksPaneDead() {
        assertFalse(SplitHostTaskSnapshot.known(Collections.emptyList()).isAlive("music.app", 17));
    }

    @Test
    public void carPlayProjectionIsTrackedByItsSavedTargetOnItsVirtualDisplay() {
        SplitHostTaskSnapshot snapshot =
                SplitHostTaskSnapshot.known(
                        Collections.singletonList(
                                SplitHostTaskSnapshot.TaskRecord.fromComponent(
                                        1, CarPlayTarget.PACKAGE, CarPlayTarget.PROJECTION, 17)));

        assertTrue(snapshot.isAlive(CarPlayTarget.ID, 17));
        assertFalse(snapshot.isAlive(CarPlayTarget.ID, 18));
    }

    @Test
    public void dashboardAndLauncherDoNotKeepCarPlayPaneAlive() {
        SplitHostTaskSnapshot snapshot =
                SplitHostTaskSnapshot.known(
                        Arrays.asList(
                                SplitHostTaskSnapshot.TaskRecord.fromComponent(
                                        1,
                                        CarPlayTarget.PACKAGE,
                                        CarPlayTarget.PACKAGE + ".MainActivity",
                                        17),
                                SplitHostTaskSnapshot.TaskRecord.fromComponent(
                                        2, CarPlayTarget.PACKAGE, CarPlayTarget.LAUNCHER, 17)));

        assertFalse(snapshot.isAlive(CarPlayTarget.ID, 17));
        assertTrue(snapshot.isAlive(CarPlayTarget.PACKAGE, 17));
    }

    @Test
    public void unreadableCarPlayDisplayKeepsSupervisionFailOpen() {
        SplitHostTaskSnapshot snapshot =
                SplitHostTaskSnapshot.known(
                        Collections.singletonList(
                                SplitHostTaskSnapshot.TaskRecord.fromComponent(
                                        1, CarPlayTarget.PACKAGE, CarPlayTarget.PROJECTION, null)));

        assertTrue(snapshot.isAlive(CarPlayTarget.ID, 17));
    }
}
