package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import ru.big.town.common.EnergyWidgetProtocol;

/** Passive collector using the existing hub. UI unsubscription never changes vehicle state. */
final class EnergyWidgetController {
    private static final long STALE_MS = 20_000, SAVE_MS = 30_000;
    private final HandlerThread thread = new HandlerThread("EnergyWidgets");
    private final Handler worker;
    private final CanBusEventHub hub;
    private final CanBusEventHub.Subscription subscription;
    private final SharedPreferences prefs;
    private final EnergyHistory history = new EnergyHistory();
    private final TripFuelEstimate fuelEstimate = new TripFuelEstimate();
    private final int bootCount;
    private float lastTripKm = Float.NaN;
    private int lastTripCounter = -1;
    private long lastFuelObservationAt = -1;
    private final float[][] values = new float[EnergyTelemetrySample.COUNT][];
    private final long[] received = {-1, -1, -1, -1, -1};
    private Messenger client;
    private boolean connected, dirty, closed;
    private long nextQuery, lastSave;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= nextQuery) { hub.requestEnergySnapshot(); nextQuery = now + 5000; }
            collect(now);
            publish(now);
            if (dirty && now - lastSave >= SAVE_MS) save(now);
            worker.postDelayed(this, 1000);
        }
    };

    EnergyWidgetController(Context context) {
        prefs = context.getSharedPreferences("EnergyWidgetHistory", Context.MODE_PRIVATE);
        bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        hub = CanBusEventHub.get(context);
        thread.start(); worker = new Handler(thread.getLooper());
        for (int i=0; i<EnergyTelemetrySample.COUNT; i++) values[i] = EnergyTelemetrySample.unavailable(i).values;
        worker.post(this::restore);
        subscription = hub.subscribe(CanBusEventRouter.INTEREST_CONNECTION
                | CanBusEventRouter.INTEREST_ENERGY_TELEMETRY, null, worker, this::onEvent);
        worker.post(tick);
    }

    void handle(Message msg) {
        int what = msg.what; Messenger reply = msg.replyTo;
        worker.post(() -> {
            if (closed) return;
            if (what == EnergyWidgetProtocol.UNWATCH) {
                if (client != null && client.equals(reply)) client = null;
            } else if (reply != null) {
                client = reply; hub.requestEnergySnapshot(); publish(SystemClock.elapsedRealtime());
            }
        });
    }

    private void onEvent(CanBusEvent event) {
        if (closed) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION
                || event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            connected = event.kind == CanBusEvent.Kind.CONNECTION;
            Arrays.fill(received, -1); history.breakSegment();
            if (connected) hub.requestEnergySnapshot();
            publish(SystemClock.elapsedRealtime());
        } else if (event.telemetry != null) {
            int kind = event.telemetry.kind;
            values[kind] = event.telemetry.values.clone();
            received[kind] = event.elapsedRealtime;
            if (kind == EnergyTelemetrySample.TRIP && Float.isFinite(values[kind][0])) {
                float km = values[kind][0];
                int counter = event.telemetry.tripCounter;
                if ((Float.isFinite(lastTripKm) && km < lastTripKm - .05f)
                        || (counter >= 0 && lastTripCounter >= 0 && counter < lastTripCounter)) {
                    history.clear(); fuelEstimate.clear(); lastFuelObservationAt = -1;
                    // The fuel reading must belong to the new trip, not to the previous snapshot.
                    received[EnergyTelemetrySample.FUEL] = -1;
                    dirty = true;
                }
                lastTripKm = km;
                if (counter >= 0) lastTripCounter = counter;
            }
            collect(event.elapsedRealtime);
        }
    }

    private boolean fresh(int kind, long now) {
        return connected && received[kind] >= 0 && now >= received[kind] && now - received[kind] <= STALE_MS;
    }

    private float current(int kind, int index, long now) {
        return fresh(kind, now) ? values[kind][index] : Float.NaN;
    }

    private void collect(long now) {
        if (!connected) { history.breakSegment(); return; }
        float km = current(EnergyTelemetrySample.TRIP, 0, now);
        float soc = current(EnergyTelemetrySample.SOC, 0, now);
        float fuel = current(EnergyTelemetrySample.FUEL, 0, now);
        dirty |= history.sample(km, soc, fuel, now, received[EnergyTelemetrySample.TRIP],
                received[EnergyTelemetrySample.SOC], received[EnergyTelemetrySample.FUEL]);
        if (Float.isFinite(km) && Float.isFinite(fuel)) {
            // A long unobserved interval could hide a refill or another trip: start a labelled
            // partial-trip estimate instead of inventing the missing fuel history.
            if (lastFuelObservationAt >= 0 && (now < lastFuelObservationAt
                    || now - lastFuelObservationAt > 60_000)) fuelEstimate.clear();
            fuelEstimate.observe(km, fuel);
            lastFuelObservationAt = now;
            dirty = true;
        }
    }

    private void publish(long now) {
        if (client == null) return;
        Bundle b = new Bundle();
        b.putInt(EnergyWidgetProtocol.SCHEMA, EnergyWidgetProtocol.VERSION);
        b.putBoolean(EnergyWidgetProtocol.CONNECTED, connected);
        b.putLong(EnergyWidgetProtocol.UPDATED, now);
        b.putFloatArray(EnergyWidgetProtocol.LEVELS, new float[]{
                current(EnergyTelemetrySample.SOC, 0, now), current(EnergyTelemetrySample.FUEL, 0, now)});
        boolean fuelReady = Float.isFinite(current(EnergyTelemetrySample.FUEL, 0, now))
                && Float.isFinite(current(EnergyTelemetrySample.TRIP, 0, now));
        b.putFloatArray(EnergyWidgetProtocol.TRIP, new float[]{
                current(EnergyTelemetrySample.TRIP, 0, now), current(EnergyTelemetrySample.TRIP, 1, now),
                fuelReady ? fuelEstimate.average() : Float.NaN});
        b.putFloat(EnergyWidgetProtocol.FUEL_ESTIMATE_KM, fuelReady ? fuelEstimate.distanceKm() : Float.NaN);
        b.putFloatArray(EnergyWidgetProtocol.TIRES, fresh(EnergyTelemetrySample.TIRES, now)
                ? values[EnergyTelemetrySample.TIRES].clone() : EnergyTelemetrySample.unavailable(EnergyTelemetrySample.TIRES).values);
        b.putFloatArray(EnergyWidgetProtocol.ODOMETER, new float[]{current(EnergyTelemetrySample.ODOMETER, 0, now)});
        List<EnergyHistory.Point> points = history.points();
        int n = points.size();
        float[] x = new float[n], ev = new float[n], fuel = new float[n]; boolean[] gaps = new boolean[n];
        for (int i=0;i<n;i++) { EnergyHistory.Point p = points.get(i);
            x[i]=p.km; ev[i]=p.ev; fuel[i]=p.fuel; gaps[i]=p.gap; }
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_X,x); b.putFloatArray(EnergyWidgetProtocol.HISTORY_EV,ev);
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_FUEL,fuel); b.putBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK,gaps);
        Message out = Message.obtain(null, EnergyWidgetProtocol.STATE); out.setData(b);
        try { client.send(out); } catch (RemoteException e) { client = null; }
    }

    private void save(long now) {
        JSONArray out = new JSONArray();
        try {
            for (EnergyHistory.Point p : history.points()) {
                JSONArray row = new JSONArray(); row.put(p.km);
                row.put(Float.isFinite(p.ev) ? (Object) p.ev : org.json.JSONObject.NULL);
                row.put(Float.isFinite(p.fuel) ? (Object) p.fuel : org.json.JSONObject.NULL);
                row.put(p.gap); out.put(row);
            }
            JSONArray fuel = new JSONArray();
            for (float v : fuelEstimate.snapshot()) fuel.put(Float.isFinite(v) ? (Object) v : org.json.JSONObject.NULL);
            prefs.edit().putString("levelsV1",out.toString()).putString("fuelEstimateV1",fuel.toString())
                    .putInt("levelsBoot",bootCount).putFloat("levelsTripKm",lastTripKm)
                    .putInt("levelsTripCounter",lastTripCounter)
                    .putLong("fuelObservationAt",lastFuelObservationAt).apply();
            dirty=false; lastSave=now;
        } catch (Exception e) { Log.w("EnergyWidgets", "Cannot persist history", e); }
    }

    private void restore() {
        try {
            // Consumption pointsV1/V2 have different units and must never be imported as levels.
            // A different/unknown boot cannot establish that the same OEM trip is continuing.
            if (bootCount < 0 || prefs.getInt("levelsBoot", -1) != bootCount) return;
            lastTripKm = prefs.getFloat("levelsTripKm",Float.NaN);
            lastTripCounter = prefs.getInt("levelsTripCounter",-1);
            lastFuelObservationAt = prefs.getLong("fuelObservationAt",-1);
            JSONArray savedFuel = new JSONArray(prefs.getString("fuelEstimateV1","[]"));
            float[] saved = new float[savedFuel.length()];
            for (int i=0;i<saved.length;i++) saved[i] = (float) savedFuel.optDouble(i,Double.NaN);
            fuelEstimate.restore(saved);
            JSONArray rows = new JSONArray(prefs.getString("levelsV1","[]"));
            if (rows.length() > EnergyHistory.MAX_POINTS) return;
            List<EnergyHistory.Point> points = new ArrayList<>();
            for (int i=0;i<rows.length();i++) { JSONArray r=rows.getJSONArray(i);
                points.add(new EnergyHistory.Point((float)r.getDouble(0),
                        (float)r.optDouble(1,Double.NaN),(float)r.optDouble(2,Double.NaN),r.getBoolean(3))); }
            history.restore(points);
        } catch (Exception e) { history.clear(); fuelEstimate.clear(); }
    }

    void close() {
        subscription.close();
        worker.post(() -> { closed=true; worker.removeCallbacks(tick); if (dirty) save(SystemClock.elapsedRealtime());
            client=null; thread.quitSafely(); });
    }
}
