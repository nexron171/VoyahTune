package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded user commands; success requires OEM readback and never optimistic state. */
final class CarPlayClimateController implements AutoCloseable {
    private final Context context;
    private final CanBusEventHub hub;
    private final Handler handler;
    private final Runnable changed;
    private final ThreadPoolExecutor commands =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(16),
                    task -> new Thread(task, "CarPlayClimateCommand"));
    private CanBusEventHub.Subscription subscription;
    private volatile long generation;
    private Messenger owner;
    private Bundle state = new Bundle();
    private long sampledAt;
    private Bundle comfortState = new Bundle();
    private long comfortSampledAt;
    private Object comfortQuery;
    private final Runnable poll =
            new Runnable() {
                @Override
                public void run() {
                    if (owner == null) {
                        return;
                    }
                    if (sampledAt > 0 && SystemClock.elapsedRealtime() - sampledAt > 5000) {
                        state.clear();
                        sampledAt = 0;
                        changed.run();
                    }
                    if (comfortSampledAt > 0
                            && SystemClock.elapsedRealtime() - comfortSampledAt > 5000) {
                        comfortState.clear();
                        comfortSampledAt = 0;
                        changed.run();
                    }
                    hub.requestClimateSnapshot();
                    requestComfortSnapshot();
                    handler.postDelayed(this, 1000);
                }
            };

    CarPlayClimateController(Context context, Handler handler, Runnable changed) {
        this.context = context.getApplicationContext();
        this.hub = CanBusEventHub.get(context);
        this.handler = handler;
        this.changed = changed;
    }

    void start(Messenger client) {
        if (client.equals(owner)) {
            return;
        }
        close();
        owner = client;
        subscription =
                hub.subscribe(
                        CanBusEventRouter.INTEREST_CONNECTION
                                | CanBusEventRouter.INTEREST_CLIMATE
                                | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                        OemComfortClimateState.KEYS.stream()
                                .mapToInt(key -> key.stableId)
                                .toArray(),
                        handler,
                        event -> {
                            if (owner == null) {
                                return;
                            }
                            if (event.kind == CanBusEvent.Kind.CLIMATE) {
                                sampledAt = event.elapsedRealtime;
                                state = bundle(event.climate.values());
                            } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE) {
                                // OEM publishes power and level separately; read a complete pair.
                                requestComfortSnapshot();
                                return;
                            } else {
                                generation++;
                                commands.getQueue().clear();
                                state.clear();
                                sampledAt = 0;
                                comfortState.clear();
                                comfortSampledAt = 0;
                                comfortQuery = null;
                                if (event.kind == CanBusEvent.Kind.CONNECTION) {
                                    hub.requestClimateSnapshot();
                                    requestComfortSnapshot();
                                }
                            }
                            changed.run();
                        });
        handler.post(poll);
    }

    Bundle snapshot() {
        Bundle snapshot = new Bundle(state);
        snapshot.putAll(comfortState);
        return snapshot;
    }

    private void requestComfortSnapshot() {
        if (owner == null || comfortQuery != null) {
            return;
        }
        Object query = new Object();
        comfortQuery = query;
        long expectedGeneration = generation;
        try {
            commands.execute(
                    () -> {
                        if (generation != expectedGeneration) {
                            return;
                        }
                        OemVehicleStateTransport.ComfortClimateSession session =
                                OemVehicleStateTransport.openComfortClimateSession(context);
                        OemComfortClimateState observed = session == null ? null : session.read();
                        long observedAt = SystemClock.elapsedRealtime();
                        handler.post(
                                () -> {
                                    if (comfortQuery != query || generation != expectedGeneration) {
                                        return;
                                    }
                                    comfortQuery = null;
                                    if (observed != null && observedAt >= comfortSampledAt) {
                                        comfortState = bundle(observed.values());
                                        comfortSampledAt = observedAt;
                                        changed.run();
                                    }
                                });
                    });
        } catch (RejectedExecutionException full) {
            comfortQuery = null;
        }
    }

    void handle(Message message) {
        if (owner == null || !owner.equals(message.replyTo)) {
            return;
        }
        Bundle input = message.getData();
        int request = input.getInt(CarPlayClimateProtocol.REQUEST);
        String field = input.getString(CarPlayClimateProtocol.FIELD, "");
        double value = input.getDouble(CarPlayClimateProtocol.VALUE, Double.NaN);
        long deadline =
                Math.min(
                        input.getLong(CarPlayClimateProtocol.DEADLINE),
                        SystemClock.elapsedRealtime() + 3000);
        long expectedGeneration = generation;
        Messenger recipient = owner;
        boolean comfortCommand = OemComfortClimateState.handles(field);
        if (!(comfortCommand
                ? OemComfortClimateState.valid(field, value)
                : ClimateCommand.valid(field, value))) {
            respond(recipient, request, CarPlayClimateProtocol.INVALID, expectedGeneration);
            return;
        }
        try {
            commands.execute(
                    () -> {
                        OemClimateState[] observed = {null};
                        OemComfortClimateState[] observedComfort = {null};
                        long[] observedAt = {0};
                        int result =
                                comfortCommand
                                        ? applyComfort(
                                                field,
                                                value,
                                                deadline,
                                                expectedGeneration,
                                                observedComfort,
                                                observedAt)
                                        : apply(
                                                field,
                                                value,
                                                deadline,
                                                expectedGeneration,
                                                observed,
                                                observedAt);
                        handler.post(
                                () -> {
                                    if (generation != expectedGeneration) {
                                        return;
                                    }
                                    if (observed[0] != null && observedAt[0] >= sampledAt) {
                                        state = bundle(observed[0].values());
                                        sampledAt = observedAt[0];
                                        changed.run();
                                    }
                                    if (observedComfort[0] != null
                                            && observedAt[0] >= comfortSampledAt) {
                                        comfortState = bundle(observedComfort[0].values());
                                        comfortSampledAt = observedAt[0];
                                        changed.run();
                                    }
                                    hub.requestClimateSnapshot();
                                    requestComfortSnapshot();
                                    respond(recipient, request, result, expectedGeneration);
                                });
                    });
        } catch (RejectedExecutionException full) {
            respond(recipient, request, CarPlayClimateProtocol.BUSY, expectedGeneration);
        }
    }

    private int applyComfort(
            String field,
            double target,
            long deadline,
            long expectedGeneration,
            OemComfortClimateState[] latest,
            long[] sampled) {
        if (!current(expectedGeneration, deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        OemVehicleStateTransport.ComfortClimateSession session =
                OemVehicleStateTransport.openComfortClimateSession(context);
        if (session == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        OemComfortClimateState before = session.read();
        if (before == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        latest[0] = before;
        sampled[0] = SystemClock.elapsedRealtime();
        if (before.reached(field, target)) {
            return CarPlayClimateProtocol.SUCCESS;
        }
        OemVehicleStateTransport.StateValue command = before.command(field, target);
        if (command == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        if (!current(expectedGeneration, deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        if (!session.send(command)) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        while (current(expectedGeneration, deadline)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            OemComfortClimateState observed = session.read();
            if (observed == null) {
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            latest[0] = observed;
            sampled[0] = SystemClock.elapsedRealtime();
            if (observed.reached(field, target)) {
                return CarPlayClimateProtocol.SUCCESS;
            }
        }
        return CarPlayClimateProtocol.TIMEOUT;
    }

    private int apply(
            String field,
            double target,
            long deadline,
            long expectedGeneration,
            OemClimateState[] latest,
            long[] sampled) {
        if (!current(expectedGeneration, deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        OemVehicleStateTransport.ClimateSession session =
                OemVehicleStateTransport.openClimateSession(context);
        if (session == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        OemClimateState before = session.read();
        if (before == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        latest[0] = before;
        sampled[0] = SystemClock.elapsedRealtime();
        if (ClimateCommand.reached(before, field, target)) {
            return CarPlayClimateProtocol.SUCCESS;
        }
        ClimateCommand command = ClimateCommand.from(before, field, target);
        if (command == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        boolean circulationCycle = CarPlayClimateProtocol.CYCLE_RECIRCULATION.equals(field);
        int circulationTarget = circulationCycle ? ClimateCommand.nextCirculationMode(before) : -1;
        if (!current(expectedGeneration, deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        if (!session.send(command)) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        boolean circulationAdvanceAvailable =
                circulationCycle || CarPlayClimateProtocol.RECIRCULATION.equals(field);
        while (current(expectedGeneration, deadline)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            OemClimateState observed = session.read();
            if (observed == null) {
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            latest[0] = observed;
            sampled[0] = SystemClock.elapsedRealtime();
            if (circulationCycle
                    ? observed.circulation == circulationTarget
                    : ClimateCommand.reached(observed, field, target)) {
                return CarPlayClimateProtocol.SUCCESS;
            }
            // A second pulse toward the target requires a confirmed first transition.
            if (circulationAdvanceAvailable
                    && ClimateCommand.circulationAdvanced(before, observed)) {
                circulationAdvanceAvailable = false;
                if (!current(expectedGeneration, deadline)) {
                    break;
                }
                if (!session.send(command)) {
                    return CarPlayClimateProtocol.UNAVAILABLE;
                }
            }
        }
        return CarPlayClimateProtocol.TIMEOUT;
    }

    private boolean current(long expectedGeneration, long deadline) {
        return generation == expectedGeneration && SystemClock.elapsedRealtime() < deadline;
    }

    private void respond(Messenger recipient, int request, int error, long expectedGeneration) {
        if (generation != expectedGeneration) {
            return;
        }
        Message response = Message.obtain(null, CarPlayClimateProtocol.RESULT);
        Bundle data = new Bundle();
        data.putInt(CarPlayClimateProtocol.REQUEST, request);
        data.putInt(CarPlayClimateProtocol.ERROR, error);
        response.setData(data);
        try {
            recipient.send(response);
        } catch (RemoteException disconnected) {
            close();
        }
    }

    private static Bundle bundle(Map<String, Double> values) {
        Bundle result = new Bundle();
        for (Map.Entry<String, Double> value : values.entrySet()) {
            result.putDouble(value.getKey(), value.getValue());
        }
        return result;
    }

    @Override
    public void close() {
        generation++;
        owner = null;
        handler.removeCallbacks(poll);
        commands.getQueue().clear();
        if (subscription != null) {
            subscription.close();
        }
        subscription = null;
        state.clear();
        sampledAt = 0;
        comfortState.clear();
        comfortSampledAt = 0;
        comfortQuery = null;
    }

    void destroy() {
        close();
        commands.shutdownNow();
    }
}
