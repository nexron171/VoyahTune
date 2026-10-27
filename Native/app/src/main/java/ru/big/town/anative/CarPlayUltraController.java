package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.CarPlayUltraProtocol;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Session-owned vehicle observations and commands; CAN stays behind the shared Native transport.
 */
final class CarPlayUltraController implements AutoCloseable {
    private static final OemVehicleStateTransport.StateKey[] KEYS = {
        key("FRONT_SEAT_MASS_SWITCH_LEFT", 702), key("FRONT_SEAT_MASS_SWITCH_RIGHT", 701),
        key("FRONT_SEAT_MASS_INTEN_LEFT", 706), key("FRONT_SEAT_MASS_INTEN_RIGHT", 705),
        key("FRONT_SEAT_MASS_COMMAND_LEFT", 704), key("FRONT_SEAT_MASS_COMMAND_RIGHT", 703),
        key("LAMP_SWITCH_POSITION_FB", 1114), key("DRIVING_MODE_SET", 545)
    };
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final CanBusEventHub hub;
    private final CarPlayRadioText radioText;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(16),
                    task -> new Thread(task, "CarPlayVehicleServices"));
    private CanBusEventHub.Subscription subscription;
    private volatile long generation;
    private Messenger owner;
    private Bundle state = new Bundle();
    private final Map<String, Long> sampledAt = new LinkedHashMap<>();
    private boolean querying;
    private final Runnable poll =
            new Runnable() {
                @Override
                public void run() {
                    if (owner == null) return;
                    hub.requestEnergySnapshot();
                    query();
                    handler.postDelayed(this, 2000);
                }
            };

    CarPlayUltraController(Context context, Handler handler, Runnable changed) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.changed = changed;
        hub = CanBusEventHub.get(context);
        radioText = new CarPlayRadioText(context);
    }

    private static OemVehicleStateTransport.StateKey key(String name, int id) {
        return new OemVehicleStateTransport.StateKey(name, id);
    }

    void start(Messenger recipient) {
        if (recipient.equals(owner)) return;
        close();
        owner = recipient;
        subscription =
                hub.subscribe(
                        CanBusEventRouter.INTEREST_CONNECTION
                                | CanBusEventRouter.INTEREST_ENERGY_TELEMETRY
                                | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                        Arrays.stream(KEYS).mapToInt(key -> key.stableId).toArray(),
                        handler,
                        event -> {
                            if (event.kind == CanBusEvent.Kind.CONNECTION
                                    || event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
                                generation++;
                                state.clear();
                                sampledAt.clear();
                                querying = false;
                                worker.getQueue().clear();
                                if (event.kind == CanBusEvent.Kind.CONNECTION) query();
                            } else if (event.kind == CanBusEvent.Kind.ENERGY_TELEMETRY) {
                                energy(event.telemetry);
                            } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE) {
                                query();
                            }
                            changed.run();
                        });
        handler.post(poll);
    }

    private void energy(EnergyTelemetrySample sample) {
        if (sample == null) return;
        String field;
        switch (sample.kind) {
            case EnergyTelemetrySample.TIRES:
                state.putFloatArray("tires", sample.values);
                sampledAt.put("tires", SystemClock.elapsedRealtime());
                return;
            case EnergyTelemetrySample.SOC:
                field = "battery";
                break;
            case EnergyTelemetrySample.FUEL:
                field = "fuel";
                break;
            case EnergyTelemetrySample.ODOMETER:
                field = "odometer";
                state.remove("fuelRange");
                state.remove("electricRange");
                if (sample.values.length >= 3) {
                    put("fuelRange", sample.values[1]);
                    put("electricRange", sample.values[2]);
                }
                break;
            default:
                return;
        }
        put(field, sample.values[0]);
    }

    private void put(String name, float value) {
        sampledAt.put(name, SystemClock.elapsedRealtime());
        if (Float.isFinite(value)) state.putDouble(name, value);
        else state.remove(name);
    }

    Bundle snapshot() {
        Bundle snapshot = new Bundle(state);
        long now = SystemClock.elapsedRealtime();
        for (String field : state.keySet()) {
            if (now - sampledAt.getOrDefault(field, 0L) > 6000) snapshot.remove(field);
        }
        return snapshot;
    }

    private Bundle read(OemVehicleStateTransport.Session session) {
        Integer[] values = new Integer[KEYS.length];
        for (int index = 0; index < KEYS.length; index++)
            values[index] = session.readVehicleState(KEYS[index]);
        Bundle result = new Bundle();
        for (int side = 0; side < 2; side++) {
            String prefix = side == 0 ? "DRIVER" : "PASSENGER";
            Integer enabled = values[side], level = values[side + 2], program = values[side + 4];
            Integer activeLevel = OemSeatMassageState.activeLevel(enabled, level);
            if (activeLevel != null) result.putInt(prefix + "_MASSAGE", activeLevel);
            if (program != null && program >= 1 && program <= 2)
                result.putInt(prefix + "_MASSAGE_PROGRAM", program);
        }
        if (values[6] != null && values[6] >= 0 && values[6] <= 3) {
            result.putInt(
                    "LIGHT_MODE",
                    HeadlightSelectionController.automaticActive(context) ? 3 : 3 - values[6]);
        }
        if (values[7] != null && values[7] >= 1 && values[7] <= 6)
            result.putInt("driveMode", values[7]);
        return result;
    }

    private void query() {
        if (owner == null || querying) return;
        querying = true;
        long epoch = generation;
        try {
            worker.execute(
                    () -> {
                        Bundle observed = null;
                        try {
                            if (generation == epoch)
                                observed =
                                        OemVehicleStateTransport.withSession(
                                                context, Arrays.asList(KEYS), this::read);
                        } catch (RuntimeException unavailable) {
                            /* Keep unavailable fields absent. */
                        }
                        Bundle text = generation == epoch ? radioText.read() : null;
                        Bundle result = observed;
                        handler.post(
                                () -> {
                                    if (generation != epoch) return;
                                    querying = false;
                                    for (String name :
                                            new String[] {
                                                "DRIVER_MASSAGE",
                                                "PASSENGER_MASSAGE",
                                                "DRIVER_MASSAGE_PROGRAM",
                                                "PASSENGER_MASSAGE_PROGRAM",
                                                "LIGHT_MODE",
                                                "driveMode"
                                            }) state.remove(name);
                                    if (result != null) {
                                        state.putAll(result);
                                        for (String field : result.keySet())
                                            sampledAt.put(field, SystemClock.elapsedRealtime());
                                    }
                                    state.remove("radioText");
                                    if (text != null) {
                                        state.putBundle("radioText", text);
                                        sampledAt.put("radioText", SystemClock.elapsedRealtime());
                                    }
                                    changed.run();
                                });
                    });
        } catch (RejectedExecutionException busy) {
            querying = false;
        }
    }

    void handle(Message message) {
        if (owner == null || !owner.equals(message.replyTo)) return;
        Bundle input = message.getData();
        int request = input.getInt(CarPlayClimateProtocol.REQUEST);
        String field = input.getString(CarPlayClimateProtocol.FIELD, "");
        double raw = input.getDouble(CarPlayClimateProtocol.VALUE, Double.NaN);
        int value = (int) raw;
        boolean program =
                field.equals("DRIVER_MASSAGE_PROGRAM") || field.equals("PASSENGER_MASSAGE_PROGRAM");
        boolean level = field.equals("DRIVER_MASSAGE") || field.equals("PASSENGER_MASSAGE");
        boolean cycle =
                field.equals("DRIVER_MASSAGE_CYCLE") || field.equals("PASSENGER_MASSAGE_CYCLE");
        boolean light = field.equals("LIGHT_MODE");
        long deadline =
                Math.min(
                        input.getLong(CarPlayClimateProtocol.DEADLINE),
                        SystemClock.elapsedRealtime() + 3000);
        long epoch = generation;
        Messenger recipient = owner;
        if ((!program && !level && !light && !cycle)
                || raw != value
                || cycle && value != 1
                || value < (program ? 1 : 0)
                || value > (program ? 2 : 3)) {
            respond(recipient, request, CarPlayClimateProtocol.INVALID, epoch);
            return;
        }
        try {
            worker.execute(
                    () -> {
                        int error = CarPlayClimateProtocol.UNAVAILABLE;
                        try {
                            if (live(epoch, deadline)) {
                                boolean confirmed =
                                        light
                                                ? setLight(value, epoch, deadline)
                                                : setMassage(
                                                        field, value, program, cycle, epoch,
                                                        deadline);
                                error =
                                        confirmed
                                                ? CarPlayClimateProtocol.SUCCESS
                                                : CarPlayClimateProtocol.TIMEOUT;
                            }
                        } catch (RuntimeException unavailable) {
                            /* The phone receives an unavailable result. */
                        }
                        respond(recipient, request, error, epoch);
                        handler.post(this::query);
                    });
        } catch (RejectedExecutionException busy) {
            respond(recipient, request, CarPlayClimateProtocol.BUSY, epoch);
        }
    }

    private boolean live(long epoch, long deadline) {
        return generation == epoch
                && SystemClock.elapsedRealtime() < deadline
                && !Thread.currentThread().isInterrupted();
    }

    private boolean setMassage(
            String field, int value, boolean program, boolean cycle, long epoch, long deadline) {
        Boolean confirmed =
                OemVehicleStateTransport.withSession(
                        context,
                        Arrays.asList(KEYS),
                        session -> {
                            int side = field.startsWith("PASSENGER") ? 1 : 0;
                            Integer initialPower =
                                    cycle ? session.readVehicleState(KEYS[side]) : null;
                            Integer target =
                                    cycle
                                            ? OemSeatMassageState.nextLevel(
                                                    initialPower,
                                                    session.readVehicleState(KEYS[side + 2]))
                                            : Integer.valueOf(value);
                            if (target == null) return false;
                            String observedField =
                                    cycle
                                            ? field.substring(0, field.length() - "_CYCLE".length())
                                            : field;
                            Map<OemVehicleStateTransport.StateKey, Integer> commands =
                                    new LinkedHashMap<>();
                            if (program) commands.put(KEYS[side + 4], target);
                            else {
                                if (target > 0) commands.put(KEYS[side + 2], target);
                                commands.put(KEYS[side], target == 0 ? 1 : 2);
                            }
                            for (Map.Entry<OemVehicleStateTransport.StateKey, Integer> command :
                                    commands.entrySet()) {
                                if (!live(epoch, deadline)) return false;
                                Integer observed = session.readVehicleState(command.getKey());
                                boolean settingInactiveIntensity =
                                        cycle
                                                && Integer.valueOf(1).equals(initialPower)
                                                && command.getKey() == KEYS[side + 2];
                                if (!settingInactiveIntensity
                                        && (observed == null
                                                || observed < 1
                                                || observed
                                                        > (command.getKey() == KEYS[side + 2]
                                                                ? 3
                                                                : 2))) return false;
                                if (command.getValue().equals(observed)) continue;
                                if (!live(epoch, deadline)) return false;
                                session.sendVehicleState(
                                        new OemVehicleStateTransport.StateValue(
                                                command.getKey(), command.getValue()),
                                        "CarPlay Ultra");
                                do {
                                    if (!live(epoch, deadline)) return false;
                                    if (command.getValue()
                                            .equals(session.readVehicleState(command.getKey())))
                                        break;
                                    SystemClock.sleep(100);
                                } while (true);
                            }
                            return live(epoch, deadline)
                                    && read(session).getInt(observedField, -1) == target;
                        });
        return Boolean.TRUE.equals(confirmed);
    }

    private boolean setLight(int value, long epoch, long deadline) {
        CountDownLatch sent = new CountDownLatch(1);
        boolean[] accepted = {false};
        handler.post(
                () -> {
                    if (!live(epoch, deadline)) {
                        sent.countDown();
                        return;
                    }
                    HeadlightSelectionController.select(
                            context,
                            value,
                            success -> {
                                accepted[0] = success;
                                sent.countDown();
                            });
                });
        try {
            if (!sent.await(
                            Math.max(0, deadline - SystemClock.elapsedRealtime()),
                            TimeUnit.MILLISECONDS)
                    || !accepted[0]) return false;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return false;
        }
        while (live(epoch, deadline)) {
            Bundle observed =
                    OemVehicleStateTransport.withSession(context, Arrays.asList(KEYS), this::read);
            if (observed != null && observed.getInt("LIGHT_MODE", -1) == value) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private void respond(Messenger recipient, int request, int error, long epoch) {
        handler.post(
                () -> {
                    if (generation != epoch || !recipient.equals(owner)) return;
                    Message response = Message.obtain(null, CarPlayUltraProtocol.RESULT);
                    Bundle values = new Bundle();
                    values.putInt(CarPlayClimateProtocol.REQUEST, request);
                    values.putInt(CarPlayClimateProtocol.ERROR, error);
                    response.setData(values);
                    try {
                        recipient.send(response);
                    } catch (RemoteException disconnected) {
                        close();
                    }
                });
    }

    @Override
    public void close() {
        generation++;
        owner = null;
        handler.removeCallbacks(poll);
        worker.getQueue().clear();
        if (subscription != null) subscription.close();
        subscription = null;
        querying = false;
        state.clear();
        sampledAt.clear();
    }

    void destroy() {
        close();
        worker.shutdownNow();
    }
}
