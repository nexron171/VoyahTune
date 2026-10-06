package ru.big.town.anative;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TripStoreInstrumentedTest {
    private Context context;
    private TripStore store;
    private TripSession session;
    private TripRecorder recorder;
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("trip-storage-tests.db"); store = new TripStore(context, "trip-storage-tests.db");
        session = new TripSession(); session.bootCount = 3; session.gear(true, 10000, 1000);
        recorder = new TripRecorder(); recorder.location(55, 37, 5, 1000, 1000, false);
        recorder.sample(1000000, 80, 50, 1000, 10000);
        recorder.location(55.0008, 37, 5, 7000, 7000, false);
        recorder.sample(1000100, 79, 49, 7000, 16000);
    }
    @After public void cleanup() { store.close(); context.deleteDatabase("trip-storage-tests.db"); }
    @Test public void restartRestoresTimingOdometerLevelsAndAllPendingSamplesTogether() throws Exception {
        store.save(session, recorder, null, false, 16000, 7000); store.close();
        store = new TripStore(context, "trip-storage-tests.db");
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertEquals(6000, restored.accumulatedMs);
        assertTrue(restored.inDrive); assertEquals(100, r.distanceMeters, 0); assertEquals(1000100, r.currentOdo, 0);
        assertEquals(80, r.startSoc, 0); assertEquals(79, r.endSoc, 0); assertEquals(1, r.evDrop, 0);
        try (Cursor c = store.samples(session.startWall)) { assertEquals(4, c.getCount()); }
        restored.recover(4); assertEquals(6000, restored.duration(10)); assertFalse(restored.inDrive);
    }
    @Test public void failedWriteRollsBackStateAndSamplesAndRetainsPendingDataForRetry() throws Exception {
        store.save(session, recorder, null, false, 16000, 7000);
        recorder.sequence = 0; recorder.endpoint(17000);
        try { store.save(session, recorder, null, false, 17000, 8000); fail("Duplicate sequence must fail atomically"); }
        catch (SQLiteException expected) { }
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertEquals(6000, restored.accumulatedMs);
        assertEquals(1, recorder.pending.size()); try (Cursor c = store.samples(session.startWall)) { assertEquals(4, c.getCount()); }
    }
    @Test public void finalizationCommitsHistoryAndRemovesLiveSessionInOneTransaction() throws Exception {
        JSONObject summary = store.summary(session, recorder, 16000, 7000, 43, 56);
        store.save(session, recorder, summary, true, 16000, 7000);
        assertFalse(store.restore(new TripSession(), new TripRecorder()));
        JSONObject saved = new org.json.JSONArray(store.summaries()).getJSONObject(0);
        assertEquals(.1, saved.getDouble("distanceKm"), .00001); assertEquals(.43, saved.getDouble("electricityKwh"), .00001);
        try (Cursor c = store.samples(session.startWall)) { assertEquals(4, c.getCount()); }
        store.delete(session.startWall); assertEquals("[]", store.summaries());
        try (Cursor c = store.samples(session.startWall)) { assertEquals(0, c.getCount()); }
    }
    @Test public void manualFinishPersistsHistoryAndIdleGuardThenStartsAnIndependentTripAfterReboot() throws Exception {
        session.gear(false, 17000, 8000); session.waitForMovement(1000100, 2); recorder.endpoint(17000);
        store.save(session, recorder, store.summary(session, recorder, 17000, 8000, 43, 56), true, 17000, 8000);
        store.close(); store = new TripStore(context, "trip-storage-tests.db");
        JSONObject saved = new org.json.JSONArray(store.summaries()).getJSONObject(0);
        assertEquals(7000, saved.getLong("durationMs")); assertEquals(17000, saved.getLong("end"));
        try (Cursor c = store.samples(session.startWall)) { assertEquals(5, c.getCount()); }
        TripSession idle = new TripSession(); TripRecorder next = new TripRecorder();
        assertTrue(store.restore(idle, next)); assertFalse(idle.active); assertFalse(idle.inDrive);
        assertTrue(idle.waitingForMovement); assertEquals(1000100, idle.restartOdo, 0);
        assertEquals(0, next.distanceMeters, 0); assertEquals(0, next.sequence);
        idle.recover(4); assertFalse(idle.allowsDriveStart(false, -1));
        assertFalse(idle.movementRestart(1000109, 2)); assertTrue(idle.movementRestart(1000110, 2));
        idle.gear(true, 90000, 1000); next.sample(1000110, 90, 60, 1000, 90000);
        store.save(idle, next, null, false, 91000, 2000);
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertTrue(restored.inDrive); assertFalse(restored.waitingForMovement);
        assertEquals(90000, restored.startWall); assertEquals(1000, restored.accumulatedMs); assertEquals(0, r.distanceMeters, 0);
        assertEquals(1, new org.json.JSONArray(store.summaries()).length());
        try (Cursor c = store.samples(session.startWall)) { assertEquals(5, c.getCount()); }
        try (Cursor c = store.samples(idle.startWall)) { assertEquals(1, c.getCount()); }
    }
    @Test public void failedManualFinishRollsBackBothHistoryAndIdleGuardAndCanRetry() throws Exception {
        store.save(session, recorder, null, false, 16000, 7000);
        session.gear(false, 17000, 8000); session.waitForMovement(1000100, 2);
        recorder.endpoint(17000);
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_manual_idle BEFORE INSERT ON current_trip BEGIN SELECT RAISE(ABORT,'test idle save failure'); END");
        JSONObject summary = store.summary(session, recorder, 17000, 8000, 43, 56);
        try { store.save(session, recorder, summary, true, 17000, 8000); fail("Idle state failure must roll back finalization"); }
        catch (SQLiteException expected) { }
        TripSession before = new TripSession();
        assertTrue(store.restore(before, new TripRecorder())); assertTrue(before.inDrive); assertFalse(before.waitingForMovement);
        assertEquals("[]", store.summaries()); assertEquals(1, recorder.pending.size());
        try (Cursor c = store.samples(session.startWall)) { assertEquals(4, c.getCount()); }
        store.getWritableDatabase().execSQL("DROP TRIGGER reject_manual_idle");
        store.save(session, recorder, summary, true, 18000, 9000);
        store.close(); store = new TripStore(context, "trip-storage-tests.db");
        TripSession idle = new TripSession(); assertTrue(store.restore(idle, new TripRecorder()));
        assertFalse(idle.active); assertTrue(idle.waitingForMovement); assertEquals(1000100, idle.restartOdo, 0);
        assertEquals(7000, new org.json.JSONArray(store.summaries()).getJSONObject(0).getLong("durationMs"));
        try (Cursor c = store.samples(session.startWall)) { assertEquals(5, c.getCount()); }
    }
    @Test public void deletingCompletedHistoryKeepsThePersistentManualStopGuard() throws Exception {
        session.gear(false, 17000, 8000); session.waitForMovement(1000100, 2);
        store.save(session, recorder, store.summary(session, recorder, 17000, 8000, 43, 56), true, 17000, 8000);
        store.clearHistory(); store.close(); store = new TripStore(context, "trip-storage-tests.db");
        TripSession idle = new TripSession(); assertTrue(store.restore(idle, new TripRecorder()));
        assertFalse(idle.active); assertTrue(idle.waitingForMovement); assertEquals("[]", store.summaries());
        try (Cursor c = store.samples(session.startWall)) { assertEquals(0, c.getCount()); }
        assertFalse(idle.movementRestart(1000109, 2)); assertTrue(idle.movementRestart(1000110, 2));
    }
    @Test public void oldCurrentStateWithoutMovementFieldsRemainsReadable() throws Exception {
        store.save(session, recorder, null, false, 16000, 7000);
        JSONObject old;
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT state FROM current_trip", null)) {
            assertTrue(c.moveToFirst()); old = new JSONObject(c.getString(0));
        }
        old.remove("waitingForMovement"); old.remove("restartOdo"); old.remove("restartOdometerSource");
        old.remove("energySchema");
        android.content.ContentValues row = new android.content.ContentValues(); row.put("state", old.toString());
        store.getWritableDatabase().update("current_trip", row, "id=1", null);
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertTrue(restored.inDrive); assertFalse(restored.waitingForMovement);
        assertEquals(6000, restored.accumulatedMs); assertEquals(100, r.distanceMeters, 0);
        assertTrue(Double.isNaN(restored.restartOdo));
    }
    @Test public void signedRecoveryTotalsSurviveReopenAndProduceMatchingTripSummary() throws Exception {
        recorder.sample(1000200, 80, 49, 13000, 22000); // 1 p.p. recovery offsets the preceding decrease.
        recorder.sample(1000300, 79.5, 49, 19000, 28000);
        recorder.sample(1000400, 80.5, 49, 25000, 34000);
        assertEquals(-.5, recorder.evDrop, 0);
        store.save(session, recorder, null, false, 34000, 25000);
        store.close(); store = new TripStore(context, "trip-storage-tests.db");
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertEquals(-.5, r.evDrop, 0); assertEquals(400, r.evMeters, 0);
        JSONObject summary = store.summary(restored, r, 34000, 25000, 43, 56);
        assertEquals(-.215, summary.getDouble("electricityKwh"), .00001);
        assertEquals(.56, summary.getDouble("fuelLiters"), .00001);
        r.sample(1000500, 90, 49, 31000, 40000); // New anchor, no bridging old levels.
        assertEquals(-.5, r.evDrop, 0); assertEquals(400, r.evMeters, 0);
    }
    @Test public void clearingHistoryRemovesCompletedSamplesAndPreservesCurrentTripAfterReopen() throws Exception {
        store.importHistory("[{\"start\":1,\"durationMs\":600000}]");
        store.save(session, recorder, store.summary(session, recorder, 16000, 7000, 43, 56), true, 16000, 7000);
        assertEquals(2, new org.json.JSONArray(store.summaries()).length());
        long completedStart = session.startWall;
        session = new TripSession(); session.bootCount = 3; session.gear(true, 30000, 10000);
        recorder = new TripRecorder(); recorder.location(55, 37, 5, 10000, 10000, false);
        recorder.sample(2000000, 90, 60, 10000, 30000);
        recorder.location(55.0008, 37, 5, 16000, 16000, false);
        recorder.sample(2000100, 89, 59, 16000, 36000);
        store.save(session, recorder, null, false, 36000, 16000);
        store.clearHistory(); store.close(); store = new TripStore(context, "trip-storage-tests.db");
        assertEquals("[]", store.summaries());
        TripSession restored = new TripSession(); TripRecorder r = new TripRecorder();
        assertTrue(store.restore(restored, r)); assertEquals(30000, restored.startWall);
        assertTrue(restored.inDrive); assertEquals(6000, restored.accumulatedMs);
        assertEquals(100, r.distanceMeters, 0); assertEquals(90, r.startSoc, 0); assertEquals(89, r.endSoc, 0);
        try (Cursor c = store.samples(completedStart)) { assertEquals(0, c.getCount()); }
        try (Cursor c = store.samples(session.startWall)) { assertEquals(4, c.getCount()); }
    }
}
