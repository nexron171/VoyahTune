package ru.big.town.anative;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One transactional owner for the live session, history metadata, and append-only samples. */
final class TripStore extends SQLiteOpenHelper {
    TripStore(Context context) { this(context, "trips.db"); }
    TripStore(Context context, String database) { super(context, database, null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE current_trip (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)");
        db.execSQL("CREATE TABLE trips (start INTEGER PRIMARY KEY, summary TEXT NOT NULL)");
        db.execSQL("CREATE TABLE samples (trip INTEGER NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(trip,seq))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int old, int version) { throw new IllegalStateException("Unsupported trip schema"); }

    boolean restore(TripSession s, TripRecorder r) throws JSONException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT state FROM current_trip WHERE id=1", null)) {
            if (!c.moveToFirst()) return false;
            JSONObject j = new JSONObject(c.getString(0));
            s.active = j.getBoolean("active"); s.inDrive = j.getBoolean("drive");
            s.startWall = j.getLong("start"); s.accumulatedMs = j.getLong("duration");
            s.pausedWall = j.getLong("pause"); s.checkpointWall = j.getLong("wall");
            s.checkpointElapsed = j.getLong("elapsed"); s.bootCount = j.getInt("boot");
            s.waitingForMovement = j.optBoolean("waitingForMovement", false);
            s.restartOdo = j.optDouble("restartOdo", Double.NaN);
            s.restartOdometerSource = j.optInt("restartOdometerSource", 0);
            r.distanceMeters = j.getDouble("meters"); r.sequence = j.getLong("sequence");
            r.currentOdo = j.optDouble("odometer");
            r.startSoc = j.optDouble("startSoc"); r.endSoc = j.optDouble("endSoc");
            r.startFuel = j.optDouble("startFuel"); r.endFuel = j.optDouble("endFuel");
            r.evDrop = j.getDouble("evDrop"); r.fuelDrop = j.getDouble("fuelDrop");
            r.evMeters = j.getDouble("evMeters"); r.fuelMeters = j.getDouble("fuelMeters");
            if (s.accumulatedMs < 0 || s.startWall < 0 || s.pausedWall < 0 || r.distanceMeters < 0
                    || r.sequence < 0 || !Double.isFinite(r.evDrop) || (r.evDrop < 0 && j.optInt("energySchema", 1) < 2)
                    || r.fuelDrop < 0 || r.evMeters < 0 || r.fuelMeters < 0)
                throw new JSONException("Invalid saved trip");
            return true;
        }
    }

    void save(TripSession s, TripRecorder r, JSONObject finished, boolean keep, long wall, long elapsed) throws JSONException {
        s.checkpoint(wall, elapsed);
        JSONObject j = state(s, r);
        JSONObject idle = finished != null && s.waitingForMovement ? state(s.afterFinish(), new TripRecorder()) : null;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            long seq = r.sequence;
            for (TripRecorder.Point p : r.pending) {
                JSONArray data = new JSONArray();
                for (double v : p.values) data.put(Double.isFinite(v) ? v : JSONObject.NULL);
                ContentValues row = new ContentValues(); row.put("trip", s.startWall); row.put("seq", seq++);
                row.put("kind", p.kind); row.put("data", data.toString()); db.insertOrThrow("samples", null, row);
            }
            if (finished != null) {
                if (keep) {
                    ContentValues row = new ContentValues(); row.put("start", s.startWall); row.put("summary", finished.toString());
                    db.replaceOrThrow("trips", null, row);
                } else db.delete("samples", "trip=?", new String[]{Long.toString(s.startWall)});
                if (idle == null) db.delete("current_trip", null, null);
                else saveCurrent(db, idle);
            } else {
                saveCurrent(db, j);
            }
            db.execSQL("DELETE FROM trips WHERE start NOT IN (SELECT start FROM trips ORDER BY start DESC LIMIT 10)");
            db.execSQL("DELETE FROM samples WHERE trip NOT IN (SELECT start FROM trips) AND trip<>?", new Object[]{s.startWall});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        r.sequence += r.pending.size(); r.pending.clear();
    }

    private static JSONObject state(TripSession s, TripRecorder r) throws JSONException {
        JSONObject j = new JSONObject().put("active", s.active).put("drive", s.persistedDrive()).put("start", s.startWall)
                .put("duration", s.accumulatedMs).put("pause", s.pausedWall).put("wall", s.checkpointWall).put("elapsed", s.checkpointElapsed)
                .put("boot", s.bootCount).put("meters", r.distanceMeters).put("sequence", r.sequence + r.pending.size())
                .put("energySchema", 2).put("evDrop", r.evDrop).put("fuelDrop", r.fuelDrop).put("evMeters", r.evMeters).put("fuelMeters", r.fuelMeters)
                .put("waitingForMovement", s.waitingForMovement).put("restartOdometerSource", s.restartOdometerSource);
        number(j, "restartOdo", s.restartOdo);
        number(j, "startSoc", r.startSoc); number(j, "endSoc", r.endSoc);
        number(j, "odometer", r.currentOdo);
        number(j, "startFuel", r.startFuel); number(j, "endFuel", r.endFuel);
        return j;
    }
    private static void saveCurrent(SQLiteDatabase db, JSONObject state) {
        ContentValues row = new ContentValues(); row.put("id", 1); row.put("state", state.toString());
        db.replaceOrThrow("current_trip", null, row);
    }

    JSONObject summary(TripSession s, TripRecorder r, long wall, long elapsed, float battery, float tank) throws JSONException {
        JSONObject j = new JSONObject().put("schema", 2).put("start", s.startWall).put("end", wall)
                .put("durationMs", s.duration(elapsed)).put("distanceKm", r.distanceMeters / 1000)
                .put("batteryKwh", battery).put("tankLiters", tank);
        number(j, "startSoc", r.startSoc); number(j, "endSoc", r.endSoc);
        number(j, "startFuel", r.startFuel); number(j, "endFuel", r.endFuel);
        number(j, "electricityKwh", r.evMeters > 0 ? r.evDrop * battery / 100 : Double.NaN);
        number(j, "fuelLiters", r.fuelMeters > 0 ? r.fuelDrop * tank / 100 : Double.NaN);
        return j;
    }
    static void number(JSONObject j, String key, double v) throws JSONException { j.put(key, Double.isFinite(v) ? v : JSONObject.NULL); }
    String summaries() throws JSONException {
        JSONArray a = new JSONArray();
        try (Cursor c = summariesCursor()) { while (c.moveToNext()) a.put(new JSONObject(c.getString(0))); }
        return a.toString();
    }
    Cursor summariesCursor() { return getReadableDatabase().rawQuery("SELECT summary FROM trips ORDER BY start DESC", null); }
    Cursor samples(long start) { return getReadableDatabase().rawQuery("SELECT kind,data FROM samples WHERE trip=? ORDER BY seq", new String[]{Long.toString(start)}); }
    void delete(long start) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("trips", "start=?", new String[]{Long.toString(start)});
            db.delete("samples", "trip=?", new String[]{Long.toString(start)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void clearHistory() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("DELETE FROM samples WHERE trip IN (SELECT start FROM trips)"); db.delete("trips", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void importHistory(String json) throws JSONException {
        JSONArray a = new JSONArray(json); SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (int i = 0; i < Math.min(10, a.length()); i++) {
                JSONObject j = a.getJSONObject(i); ContentValues row = new ContentValues();
                row.put("start", j.getLong("start")); row.put("summary", j.toString());
                db.insertWithOnConflict("trips", null, row, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
