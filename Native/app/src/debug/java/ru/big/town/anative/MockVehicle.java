package ru.big.town.anative;

import android.os.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Stateful vehicle simulator; compiled into debug APKs only. */
final class MockVehicle implements VehiclePort, OemVehicleStateTransport.Session, OemCommandSender.Transport {
    private final Map<Integer, Integer> states = new java.util.concurrent.ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Consumer<Bundle> trace;
    volatile boolean connected = true, accepted = true, confirm = true;
    volatile long delayMs = 120;
    volatile int speed, gear, doors, soc = 78;
    private long sequence;
    private String prepared;
    MockVehicle(Consumer<Bundle> trace) {
        this.trace = trace;
        states.put(750, 5); states.put(751, 0); states.put(711, 1);
        states.put(790, 0); states.put(1060, 0); states.put(545, 2); states.put(615, soc);
    }
    private static final class Listener {
        final int interests; final int[] ids; final Handler handler; final Consumer<CanBusEvent> callback;
        Listener(int interests, int[] ids, Handler handler, Consumer<CanBusEvent> callback) {
            this.interests=interests; this.ids=ids; this.handler=handler; this.callback=callback;
        }
        boolean accepts(CanBusEvent e) {
            if (e.kind == CanBusEvent.Kind.ENERGY_TELEMETRY)
                return (interests & CanBusEventRouter.INTEREST_ENERGY_TELEMETRY) != 0;
            if (e.kind == CanBusEvent.Kind.GEAR) return (interests & CanBusEventRouter.INTEREST_GEAR) != 0;
            if (e.kind == CanBusEvent.Kind.DOOR) return (interests & CanBusEventRouter.INTEREST_DOOR) != 0;
            if (e.kind != CanBusEvent.Kind.VEHICLE_STATE) return true;
            return (interests & CanBusEventRouter.INTEREST_VEHICLE_STATE) != 0 &&
                (ids == null || Arrays.stream(ids).anyMatch(id -> id == e.first));
        }
    }
    @Override public Subscription subscribe(int interests, int[] ids, Handler handler, Consumer<CanBusEvent> callback) {
        Listener listener = new Listener(interests, ids, handler, callback); listeners.add(listener);
        handler.post(() -> callback.accept(connectionEvent()));
        record("subscribe", interests, listeners.size());
        return () -> { listeners.remove(listener); record("unsubscribe", interests, listeners.size()); };
    }
    private synchronized CanBusEvent connectionEvent() {
        long now = SystemClock.elapsedRealtime();
        return connected ? CanBusEvent.connection(1, ++sequence, now)
            : CanBusEvent.connectionLost(2, ++sequence, now, 1);
    }
    void connection(boolean value) { connected=value; emit(connectionEvent()); }
    private void emit(CanBusEvent e) {
        for (Listener l : listeners) if (l.accepts(e)) l.handler.post(() -> { if(listeners.contains(l)) l.callback.accept(e); });
    }
    void state(int id, int value) {
        states.put(id,value);
        emit(CanBusEvent.vehicleState(CanBusEvent.Origin.LIVE,1,++sequence,SystemClock.elapsedRealtime(),id,value));
    }
    @Override public void requestEnergySnapshot() {
        record("snapshot",0,0);
        if (!connected) return;
        telemetry(EnergyTelemetrySample.SOC, soc);
        telemetry(EnergyTelemetrySample.FUEL, 54);
        telemetry(EnergyTelemetrySample.TIRES, 2.4f, 2.5f, 2.6f, 2.7f);
        telemetry(EnergyTelemetrySample.ODOMETER, 12345);
    }
    void telemetry(int kind, float... values) {
        emit(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,++sequence,SystemClock.elapsedRealtime(),new EnergyTelemetrySample(kind, values)));
    }
    @Override public synchronized <T> T session(Collection<OemVehicleStateTransport.StateKey> keys,
                    OemVehicleStateTransport.SessionOperation<T> operation) {
        record("session",0,keys.size()); return connected ? operation.run(this) : null;
    }
    @Override public Map<OemVehicleStateTransport.StateKey,Integer> driveProfile(String mode) {
        int value = "SPORT".equals(mode)?3:"OUTING".equals(mode)?4:"COMFORT".equals(mode)?2:1;
        return Collections.singletonMap(new OemVehicleStateTransport.StateKey("DRIVING_MODE_SET",545),value);
    }
    @Override public OemVehicleStateTransport.GearStatus readGearStatus() {
        record("readGear",0,gear); return connected ? new OemVehicleStateTransport.GearStatus(gear,gear) : null;
    }
    @Override public Integer readVehicleSpeed() { record("readSpeed",0,speed); return connected?speed:null; }
    @Override public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
        Integer value = key.stableId==615?Integer.valueOf(soc):states.get(key.stableId);
        record("read",key.stableId,value==null?-1:value); return connected?value:null;
    }
    @Override public OemVehicleStateTransport.Result sendVehicleState(OemVehicleStateTransport.StateValue value, String label) {
        return sendBundle(Collections.singletonMap(value.key,value.value),label);
    }
    @Override public OemVehicleStateTransport.Result sendBundle(Map<OemVehicleStateTransport.StateKey,Integer> values, String label) {
        for (Map.Entry<OemVehicleStateTransport.StateKey,Integer> entry : values.entrySet()) record("write",entry.getKey().stableId,entry.getValue());
        if (!connected || !accepted) return OemVehicleStateTransport.Result.TRANSIENT_FAILURE;
        if (confirm) clock.postDelayed(() -> {
            if (!connected) return;
            for (Map.Entry<OemVehicleStateTransport.StateKey,Integer> entry : values.entrySet()) {
                int id=entry.getKey().stableId, value=entry.getValue(); state(id,value);
                if(id==545) state(750,value==3?7:value==4?1:5);
                if(id==790) state(750,9);
            }
        },delayMs);
        return OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED;
    }
    @Override public void prepare(String field,long deadline) throws Exception {
        if(!connected || SystemClock.elapsedRealtime()>=deadline) throw new java.util.concurrent.TimeoutException();
        if(field.startsWith("UNKNOWN")) throw new UnsupportedOperationException(field);
        prepared=field;
    }
    @Override public int send(String field,int value) {
        if(!field.equals(prepared))throw new IllegalStateException("not prepared");
        Bundle b=new Bundle(); b.putString("op","oemWrite"); b.putString("field",field); b.putInt("value",value);trace.accept(b);
        prepared=null;return accepted?0:-1;
    }
    void configure(Bundle b) {
        if(b.containsKey("connected"))connection(b.getBoolean("connected"));
        accepted=b.getBoolean("accepted",accepted); confirm=b.getBoolean("confirm",confirm);
        delayMs=b.getLong("delayMs",delayMs); speed=b.getInt("speed",speed);soc=b.getInt("soc",soc);
        if(b.containsKey("gear")) { gear=b.getInt("gear"); emit(CanBusEvent.gear(CanBusEvent.Origin.LIVE,1,++sequence,SystemClock.elapsedRealtime(),gear)); }
        if(b.containsKey("doors")) { doors=b.getInt("doors"); emit(CanBusEvent.door(CanBusEvent.Origin.LIVE,1,++sequence,SystemClock.elapsedRealtime(),doors&1,doors)); }
        if(b.containsKey("stateId"))state(b.getInt("stateId"),b.getInt("stateValue"));
        if(b.getBoolean("snapshot"))requestEnergySnapshot();
    }
    void record(String op,int id,int value) { Bundle b=new Bundle();b.putString("op",op);b.putInt("id",id);b.putInt("value",value);trace.accept(b); }
    void close() { listeners.clear();clock.removeCallbacksAndMessages(null); }
}
