package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryHeatSnapshotStateTest {
    @Test
    public void unchangedH97xCacheIdentifiesProfileWithoutCallbacks() {
        BatteryHeatSnapshotState state = new BatteryHeatSnapshotState();
        state.reset(1L);
        BatteryHeatSnapshotState.Read read = state.beginRead();

        boolean h97xSeen = state.accepts(read, 1080, 0);
        boolean h97cSeen = state.accepts(read, 1295, -1)
                || state.accepts(read, 1296, -1);

        assertTrue(h97xSeen);
        assertFalse(h97cSeen);
        assertEquals(BatteryHeatAutoPolicy.PLATFORM_H97X,
                BatteryHeatAutoPolicy.platform(h97xSeen, h97cSeen));
        assertTrue(state.accepts(read, 1265, 0));
    }

    @Test
    public void absentAndFailedReadsDoNotIdentifyEitherPlatform() {
        BatteryHeatSnapshotState state = new BatteryHeatSnapshotState();
        state.reset(1L);
        BatteryHeatSnapshotState.Read read = state.beginRead();

        assertFalse(state.accepts(read, 1080, -1));
        assertFalse(state.accepts(read, 1265, null));
        assertFalse(state.accepts(read, 1295, -9999));
    }

    @Test
    public void newerFailureEventWinsWhileUnchangedProfileCanStillBeSeeded() {
        BatteryHeatSnapshotState state = new BatteryHeatSnapshotState();
        state.reset(1L);
        BatteryHeatSnapshotState.Read read = state.beginRead();
        state.recordState(1265);

        assertFalse(state.accepts(read, 1265, 0));
        assertTrue(state.accepts(read, 1080, 0));
        assertTrue(state.accepts(state.beginRead(), 1265, 0));
    }

    @Test
    public void staleInactiveReadCannotOverwriteHeatingFeedback() {
        BatteryHeatSnapshotState state = new BatteryHeatSnapshotState();
        state.reset(1L);
        state.recordState(1080);
        BatteryHeatSnapshotState.Read read = state.beginRead();
        state.recordState(1080);

        assertFalse(state.accepts(read, 1080, 0));
    }

    @Test
    public void reconnectAndDisconnectDiscardOldSnapshots() {
        BatteryHeatSnapshotState state = new BatteryHeatSnapshotState();
        state.reset(1L);
        BatteryHeatSnapshotState.Read oldRead = state.beginRead();
        state.reset(2L);

        assertFalse(state.accepts(oldRead, 1080, 0));
        assertTrue(state.accepts(state.beginRead(), 1080, 0));

        state.reset(0L);
        assertFalse(state.accepts(oldRead, 1080, 0));
        assertFalse(state.accepts(state.beginRead(), 1080, 0));
    }
}
