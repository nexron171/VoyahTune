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
import ru.big.town.common.EnergyWidgetSettings;

/** Passive collector on the existing hub, independent of dashboard visibility. */
final class EnergyWidgetController {
    private static final long STALE_MS=20_000, SAVE_MS=5_000;
    private final HandlerThread thread=new HandlerThread("EnergyWidgets");
    private final Handler worker;
    private final CanBusEventHub hub;
    private final CanBusEventHub.Subscription subscription;
    private final SharedPreferences prefs;
    private final EnergyHistory history=new EnergyHistory();
    private final EnergyConsumptionHistory consumption=new EnergyConsumptionHistory();
    private final EnergyDistanceTracker distance=new EnergyDistanceTracker();
    private float batteryKwh=43,tankLiters=56;
    private final float[][] values=new float[EnergyTelemetrySample.COUNT][];
    private final long[] received=new long[EnergyTelemetrySample.COUNT];
    private Messenger client;
    private boolean connected,dirty,closed;
    private long nextQuery,lastSave,lastCollect=-1;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(closed)return;
            long now=SystemClock.elapsedRealtime();
            if(now>=nextQuery){hub.requestEnergySnapshot();nextQuery=now+5000;}
            collect(now);publish(now);
            if(dirty&&now-lastSave>=SAVE_MS)save(now);
            worker.postDelayed(this,1000);
        }
    };
    EnergyWidgetController(Context context) {
        prefs=context.getSharedPreferences("EnergyWidgetHistory",Context.MODE_PRIVATE);
        Arrays.fill(received,-1);
        hub=CanBusEventHub.get(context);
        thread.start();worker=new Handler(thread.getLooper());
        for(int i=0;i<EnergyTelemetrySample.COUNT;i++)values[i]=EnergyTelemetrySample.unavailable(i).values;
        worker.post(this::restore);
        subscription=hub.subscribe(CanBusEventRouter.INTEREST_CONNECTION|CanBusEventRouter.INTEREST_ENERGY_TELEMETRY,
                null,worker,this::onEvent);
        worker.post(tick);
    }
    void handle(Message msg) {
        int what=msg.what;Messenger reply=msg.replyTo;
        Bundle config=what==EnergyWidgetProtocol.CONFIGURE?new Bundle(msg.getData()):null;
        worker.post(()->{
            if(closed)return;
            if(what==EnergyWidgetProtocol.CONFIGURE) {
                if(config.getInt(EnergyWidgetProtocol.SCHEMA)!=EnergyWidgetProtocol.VERSION)return;
                float battery=config.getFloat(EnergyWidgetProtocol.BATTERY_KWH,Float.NaN);
                float tank=config.getFloat(EnergyWidgetProtocol.TANK_LITERS,Float.NaN);
                if(!EnergyWidgetSettings.validCapacity(battery)||!EnergyWidgetSettings.validCapacity(tank))return;
                if(battery!=batteryKwh||tank!=tankLiters){batteryKwh=battery;tankLiters=tank;dirty=true;save(SystemClock.elapsedRealtime());}
                publish(SystemClock.elapsedRealtime());
            } else if(what==EnergyWidgetProtocol.UNWATCH) {
                if(client!=null&&client.equals(reply))client=null;
            } else if(what==EnergyWidgetProtocol.WATCH&&reply!=null) {
                client=reply;hub.requestEnergySnapshot();publish(SystemClock.elapsedRealtime());
            }
        });
    }
    private void breakObservation() {
        history.breakSegment();
        dirty|=consumption.breakSegment();
    }
    private void onEvent(CanBusEvent event) {
        if(closed)return;
        if(event.kind==CanBusEvent.Kind.CONNECTION||event.kind==CanBusEvent.Kind.CONNECTION_LOST) {
            connected=event.kind==CanBusEvent.Kind.CONNECTION;
            Arrays.fill(received,-1);breakObservation();distance.breakSegment();lastCollect=-1;
            if(connected)hub.requestEnergySnapshot();
            publish(SystemClock.elapsedRealtime());
        } else if(event.telemetry!=null) {
            int kind=event.telemetry.kind;
            if(kind==EnergyTelemetrySample.PRECISE_ODOMETER)return;
            values[kind]=event.telemetry.values.clone();
            if(kind==EnergyTelemetrySample.TRIP) {
                if(!EnergyHistory.fresh(event.elapsedRealtime,received[kind])) {
                    distance.breakSegment();breakObservation();
                }
                if(distance.observe(values[kind][0],event.telemetry.tripCounter)) {
                    history.breakSegment();
                    dirty|=consumption.breakSegment();
                    // New-trip sensor levels must not be taken from the previous trip snapshot.
                    received[EnergyTelemetrySample.SOC]=received[EnergyTelemetrySample.FUEL]=-1;
                }
                dirty=true;
            }
            received[kind]=event.elapsedRealtime;
            collect(event.elapsedRealtime);
        }
    }
    private boolean fresh(int kind,long now) {
        return connected&&received[kind]>=0&&now>=received[kind]&&now-received[kind]<=STALE_MS;
    }
    private float current(int kind,int index,long now) {return fresh(kind,now)?values[kind][index]:Float.NaN;}
    private void collect(long now) {
        if(!connected){breakObservation();return;}
        if(lastCollect>=0&&(now<lastCollect||now-lastCollect>EnergyHistory.MAX_SAMPLE_AGE_MS))breakObservation();
        lastCollect=now;
        if(!EnergyHistory.fresh(now,received[EnergyTelemetrySample.TRIP])
                ||!Float.isFinite(values[EnergyTelemetrySample.TRIP][0])){breakObservation();distance.breakSegment();return;}
        double km=distance.distance();
        float soc=EnergyHistory.fresh(now,received[EnergyTelemetrySample.SOC])?values[EnergyTelemetrySample.SOC][0]:Float.NaN;
        float fuel=EnergyHistory.fresh(now,received[EnergyTelemetrySample.FUEL])?values[EnergyTelemetrySample.FUEL][0]:Float.NaN;
        dirty|=history.sample(km,soc,fuel,now,received[EnergyTelemetrySample.TRIP],
                received[EnergyTelemetrySample.SOC],received[EnergyTelemetrySample.FUEL]);
        dirty|=consumption.sample(km,soc,fuel,now,received[EnergyTelemetrySample.TRIP],
                received[EnergyTelemetrySample.SOC],received[EnergyTelemetrySample.FUEL]);
    }
    private void publish(long now) {
        if(client==null)return;
        Bundle b=new Bundle();
        b.putInt(EnergyWidgetProtocol.SCHEMA,EnergyWidgetProtocol.VERSION);
        b.putBoolean(EnergyWidgetProtocol.CONNECTED,connected);b.putLong(EnergyWidgetProtocol.UPDATED,now);
        b.putFloat(EnergyWidgetProtocol.BATTERY_KWH,batteryKwh);b.putFloat(EnergyWidgetProtocol.TANK_LITERS,tankLiters);
        b.putFloatArray(EnergyWidgetProtocol.LEVELS,new float[]{current(EnergyTelemetrySample.SOC,0,now),current(EnergyTelemetrySample.FUEL,0,now)});
        double[] trip=TripStatsService.currentEnergy();
        b.putFloatArray(EnergyWidgetProtocol.TRIP,new float[]{(float)trip[0],
                EnergyWidgetSettings.electricityAverage(trip[1],trip[3],batteryKwh),
                EnergyWidgetSettings.average(trip[2],trip[4],tankLiters)});
        b.putFloatArray(EnergyWidgetProtocol.TRIP_OBSERVED_KM,new float[]{(float)trip[3],(float)trip[4]});
        b.putFloatArray(EnergyWidgetProtocol.TIRES,fresh(EnergyTelemetrySample.TIRES,now)?values[EnergyTelemetrySample.TIRES].clone():EnergyTelemetrySample.unavailable(EnergyTelemetrySample.TIRES).values);
        b.putFloatArray(EnergyWidgetProtocol.ODOMETER,new float[]{current(EnergyTelemetrySample.ODOMETER,0,now)});
        List<EnergyHistory.Point> points=history.points();int n=points.size();
        float[] x=new float[n],ev=new float[n],fuel=new float[n];boolean[] gaps=new boolean[n];
        double[] evDrop=new double[n],fuelDrop=new double[n],evKm=new double[n],fuelKm=new double[n];
        double[] startEvDrop=new double[n],startFuelDrop=new double[n],startEvKm=new double[n],startFuelKm=new double[n];
        for(int i=0;i<n;i++){EnergyHistory.Point p=points.get(i);x[i]=p.km;ev[i]=p.ev;fuel[i]=p.fuel;gaps[i]=p.gap;
            evDrop[i]=p.evDrop;fuelDrop[i]=p.fuelDrop;evKm[i]=p.evKm;fuelKm[i]=p.fuelKm;
            startEvDrop[i]=p.startEvDrop;startFuelDrop[i]=p.startFuelDrop;startEvKm[i]=p.startEvKm;startFuelKm[i]=p.startFuelKm;}
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_X,x);b.putFloatArray(EnergyWidgetProtocol.HISTORY_EV,ev);
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_FUEL,fuel);b.putBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK,gaps);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP,evDrop);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP,fuelDrop);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM,evKm);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM,fuelKm);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP,startEvDrop);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP,startFuelDrop);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM,startEvKm);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM,startFuelKm);
        b.putDouble(EnergyWidgetProtocol.RECORDED_KM,distance.distance());
        List<EnergyConsumptionHistory.Point> intervals=consumption.points();int count=intervals.size();
        double[] starts=new double[count],ends=new double[count];float[] usedEv=new float[count],usedFuel=new float[count];
        boolean[] breaks=new boolean[count];
        for(int i=0;i<count;i++){EnergyConsumptionHistory.Point p=intervals.get(i);starts[i]=p.start;ends[i]=p.end;
            usedEv[i]=p.electricity(batteryKwh);usedFuel[i]=p.fuel(tankLiters);breaks[i]=p.gap;}
        b.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START,starts);b.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END,ends);
        b.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV,usedEv);b.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL,usedFuel);
        b.putBooleanArray(EnergyWidgetProtocol.CONSUMPTION_BREAK,breaks);
        Message out=Message.obtain(null,EnergyWidgetProtocol.STATE);out.setData(b);
        try{client.send(out);}catch(RemoteException e){client=null;}
    }
    private void save(long now) {
        try {
            EnergyHistoryState state=new EnergyHistoryState();state.points=history.points();state.distance=distance.snapshot();
            state.consumption=consumption.snapshot();
            // The legacy trip fields remain zero for format compatibility; TripStore owns the trip.
            if(prefs.edit().putString("levelsV2",state.encode())
                    .putFloat(EnergyWidgetSettings.BATTERY_KEY,batteryKwh).putFloat(EnergyWidgetSettings.TANK_KEY,tankLiters)
                    .remove("levelsV1").remove("fuelEstimateV1").commit())dirty=false;
            lastSave=now;
        }catch(Exception e){Log.w("EnergyWidgets","Cannot persist history",e);}
    }
    private void restore() {
        batteryKwh=storedCapacity(EnergyWidgetSettings.BATTERY_KEY,43);
        tankLiters=storedCapacity(EnergyWidgetSettings.TANK_KEY,56);
        try {
            if(prefs.contains("levelsV2")) {
                EnergyHistoryState state=EnergyHistoryState.decode(prefs.getString("levelsV2",""));
                history.restore(state.points);distance.restore(state.distance);
                consumption.restore(state.consumption);
            } else {
                // Only prior level history is migrated. pointsV1/V2 and old net fuel totals are different units/semantics.
                JSONArray rows=new JSONArray(prefs.getString("levelsV1","[]"));
                if(rows.length()>EnergyHistory.MAX_POINTS)return;
                List<EnergyHistory.Point> points=new ArrayList<>();
                for(int i=0;i<rows.length();i++){JSONArray r=rows.getJSONArray(i);points.add(new EnergyHistory.Point((float)r.getDouble(0),
                        (float)r.optDouble(1,Double.NaN),(float)r.optDouble(2,Double.NaN),r.getBoolean(3)));}
                history.restore(points);
                if(!history.points().isEmpty()){
                    float last=history.points().get(history.points().size()-1).km;
                    float trip=prefs.getFloat("levelsTripKm",last);
                    distance.restore(new double[]{last,Float.isFinite(trip)&&trip>=0?trip:last,prefs.getInt("levelsTripCounter",-1)});
                }
                dirty=true;
            }
        }catch(Exception e){history.clear();Log.w("EnergyWidgets","Invalid saved history",e);}
        // No values/received timestamps survive restoration; first fresh samples anchor new segments.
        breakObservation();distance.breakSegment();
    }
    private float storedCapacity(String key,float fallback) {
        try{float value=prefs.getFloat(key,fallback);return EnergyWidgetSettings.validCapacity(value)?value:fallback;}
        catch(ClassCastException ignored){return fallback;}
    }
    void close() {
        subscription.close();worker.post(()->{closed=true;worker.removeCallbacks(tick);
            if(dirty)save(SystemClock.elapsedRealtime());client=null;thread.quitSafely();});
    }
}
