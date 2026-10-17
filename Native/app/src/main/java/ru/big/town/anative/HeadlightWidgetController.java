package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;

import ru.big.town.common.HeadlightWidgetProtocol;
import ru.big.town.common.MessengerSubscription;

import java.util.Collections;

final class HeadlightWidgetController {
    private static final OemVehicleStateTransport.StateKey SWITCH_POSITION =
            new OemVehicleStateTransport.StateKey("LAMP_SWITCH_POSITION_FB", 1114);
    private static final long CONFIRMATION_TIMEOUT_MS = 5000;

    private final Context context;
    private final VehiclePort vehicle;
    private final HandlerThread thread = new HandlerThread("HeadlightWidget");
    private final Handler worker;
    private final MessengerSubscription client = new MessengerSubscription();
    private final HeadlightWidgetState state = new HeadlightWidgetState();
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            this::onPreferenceChanged;
    private VehiclePort.Subscription subscription;
    private Messenger owner;
    private int clientSession;
    private int switchPosition = -1;
    private long generation;
    private boolean commandInFlight;
    private volatile boolean closed;

    private final Runnable timeout =
            () -> {
                refresh();
                state.timeout();
                publish();
            };

    HeadlightWidgetController(Context context) {
        this.context = context.getApplicationContext();
        vehicle = VehiclePort.oem(context);
        preferences = context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
        HeadlightCanTransport.initialize(context);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    private void onPreferenceChanged(SharedPreferences saved, String key) {
        if (HeadlightSelectionController.SAVED_MODE.equals(key) || "autoLight".equals(key)) {
            worker.post(
                    () -> {
                        if (subscription != null && !closed) {
                            observePosition(switchPosition);
                            publish();
                        }
                    });
        }
    }

    void handle(Message request) {
        int what = request.what;
        int mode = request.arg1;
        int session = request.arg2;
        Messenger reply = request.replyTo;
        worker.post(
                () -> {
                    if (closed || reply == null) {
                        return;
                    }
                    boolean current = reply.equals(owner) && session == clientSession;
                    if (what == HeadlightWidgetProtocol.UNWATCH) {
                        if (current) {
                            stopWatching();
                        }
                    } else if (what == HeadlightWidgetProtocol.WATCH) {
                        if (!current) {
                            stopWatching();
                        }
                        owner = reply;
                        clientSession = session;
                        client.set(reply);
                        if (subscription == null) {
                            preferences.registerOnSharedPreferenceChangeListener(
                                    preferenceListener);
                            subscription =
                                    vehicle.subscribe(
                                            CanBusEventRouter.INTEREST_CONNECTION
                                                    | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                                            new int[] {SWITCH_POSITION.stableId},
                                            worker,
                                            this::onEvent);
                        }
                        refresh();
                        publish();
                    } else if (what == HeadlightWidgetProtocol.SELECT && current) {
                        select(mode);
                    }
                });
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) {
            return;
        }
        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            switchPosition = -1;
            state.reset("Нет связи с автомобилем");
            worker.removeCallbacks(timeout);
        } else if (event.kind == CanBusEvent.Kind.CONNECTION) {
            refresh();
        } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE
                && event.first == SWITCH_POSITION.stableId) {
            observePosition(event.second);
        }
        publish();
    }

    private void refresh() {
        Integer position =
                vehicle.session(
                        Collections.singletonList(SWITCH_POSITION),
                        session -> session.readVehicleState(SWITCH_POSITION));
        observePosition(position == null ? -1 : position);
    }

    private void observePosition(int position) {
        switchPosition = position;
        boolean automatic = HeadlightSelectionController.automaticActive(context);
        state.observed(
                automatic && HeadlightWidgetState.modeFromFeedback(position) >= 0 ? 0 : position);
    }

    private void select(int mode) {
        if (commandInFlight || !state.select(mode)) {
            publish();
            return;
        }
        commandInFlight = true;
        long requestGeneration = generation;
        publish();
        HeadlightSelectionController.select(
                context,
                mode,
                accepted ->
                        worker.post(
                                () -> {
                                    commandInFlight = false;
                                    if (closed) {
                                        return;
                                    }
                                    if (requestGeneration != generation) {
                                        if (subscription != null) {
                                            publish();
                                        }
                                        return;
                                    }
                                    if (accepted) {
                                        refresh();
                                    }
                                    state.sent(accepted);
                                    worker.removeCallbacks(timeout);
                                    if (state.pending >= 0) {
                                        worker.postDelayed(timeout, CONFIRMATION_TIMEOUT_MS);
                                    }
                                    publish();
                                }));
    }

    private void publish() {
        if (closed) {
            return;
        }
        Bundle data = new Bundle();
        boolean automatic = HeadlightSelectionController.automaticActive(context);
        data.putInt(
                HeadlightWidgetProtocol.MODE,
                automatic && state.mode >= 0 ? HeadlightWidgetProtocol.AUTO : state.mode);
        data.putInt(HeadlightWidgetProtocol.PENDING, state.pending);
        data.putBoolean(HeadlightWidgetProtocol.AVAILABLE, state.available() && !commandInFlight);
        data.putString(
                HeadlightWidgetProtocol.MESSAGE,
                automatic && state.message.isEmpty() ? "Автосвет" : state.message);
        Message response = Message.obtain(null, HeadlightWidgetProtocol.STATE, 0, clientSession);
        response.setData(data);
        if (!client.send(response)) {
            stopWatching();
        }
    }

    private void stopWatching() {
        ++generation;
        worker.removeCallbacks(timeout);
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        client.clear();
        owner = null;
        switchPosition = -1;
        state.reset("Нет данных фар");
    }

    void close() {
        closed = true;
        worker.post(
                () -> {
                    stopWatching();
                    thread.quitSafely();
                });
    }
}
