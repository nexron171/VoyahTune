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
    private final float[][] values = new float[4][];
    private final long[] received = {-1, -1, -1, -1};
    private Messenger client;
    private boolean connected, dirty, closed;
    private long nextQuery, lastSave;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= nextQuery) { hub.requestEnergySnapshot(); nextQuery = now + 5000; }
            if (!fresh(0, now) || !fresh(1, now)) history.breakSegment();
            publish(now);
            if (dirty && now - lastSave >= SAVE_MS) save(now);
            worker.postDelayed(this, 1000);
        }
    };

    EnergyWidgetController(Context context) {
        prefs = context.getSharedPreferences("EnergyWidgetHistory", Context.MODE_PRIVATE);
        hub = CanBusEventHub.get(context);
        thread.start(); worker = new Handler(thread.getLooper());
        for (int i=0; i<4; i++) values[i] = EnergyTelemetrySample.unavailable(i).values;
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
            values[kind] = event.telemetry.values.clone(); received[kind] = event.elapsedRealtime;
            if (kind == EnergyTelemetrySample.INSTANT || kind == EnergyTelemetrySample.TRIP) {
                dirty |= history.sample(values[1][0], values[0][0], values[0][1],
                        event.elapsedRealtime, received[1], received[0]);
            }
        }
    }

    private boolean fresh(int kind, long now) {
        return connected && received[kind] >= 0 && now >= received[kind] && now - received[kind] <= STALE_MS;
    }

    private void publish(long now) {
        if (client == null) return;
        Bundle b = new Bundle();
        b.putInt(EnergyWidgetProtocol.SCHEMA, EnergyWidgetProtocol.VERSION);
        b.putBoolean(EnergyWidgetProtocol.CONNECTED, connected);
        b.putLong(EnergyWidgetProtocol.UPDATED, now);
        String[] keys = {EnergyWidgetProtocol.INSTANT, EnergyWidgetProtocol.TRIP,
                EnergyWidgetProtocol.TIRES, EnergyWidgetProtocol.ODOMETER};
        for (int i=0; i<4; i++) b.putFloatArray(keys[i], fresh(i, now)
                ? values[i].clone() : EnergyTelemetrySample.unavailable(i).values);
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
            prefs.edit().putString("pointsV1",out.toString()).apply();
            dirty=false; lastSave=now;
        } catch (Exception e) { Log.w("EnergyWidgets", "Cannot persist history", e); }
    }

    private void restore() {
        try {
            JSONArray rows = new JSONArray(prefs.getString("pointsV1","[]"));
            if (rows.length() > EnergyHistory.MAX_POINTS) return;
            List<EnergyHistory.Point> points = new ArrayList<>();
            for (int i=0;i<rows.length();i++) { JSONArray r=rows.getJSONArray(i);
                points.add(new EnergyHistory.Point((float)r.getDouble(0),
                        (float)r.optDouble(1,Double.NaN),(float)r.optDouble(2,Double.NaN),r.getBoolean(3))); }
            history.restore(points);
        } catch (Exception e) { history.clear(); }
    }

    void close() {
        subscription.close();
        worker.post(() -> { closed=true; worker.removeCallbacks(tick); if (dirty) save(SystemClock.elapsedRealtime());
            client=null; thread.quitSafely(); });
    }
}
