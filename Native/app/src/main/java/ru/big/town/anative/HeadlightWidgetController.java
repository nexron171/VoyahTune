package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;

import ru.big.town.common.HeadlightWidgetProtocol;

import java.util.Collections;

final class HeadlightWidgetController {
    private static final OemVehicleStateTransport.StateKey SWITCH_POSITION =
            new OemVehicleStateTransport.StateKey("LAMP_SWITCH_POSITION_FB", 1114);
    private static final long CONFIRMATION_TIMEOUT_MS = 5000;

    private final Context context;
    private final VehiclePort vehicle;
    private final HandlerThread thread = new HandlerThread("HeadlightWidget");
    private final Handler worker;
    private final WidgetClients clients = new WidgetClients();
    private final HeadlightWidgetState state = new HeadlightWidgetState();
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            this::onPreferenceChanged;
    private VehiclePort.Subscription subscription;
    private final RearFogWidgetState fog = new RearFogWidgetState();
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

    private final Runnable fogTimeout =
            () -> {
                refreshFog();
                fog.timeout();
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
                    boolean current = clients.isWatching(reply, session);
                    if (what == HeadlightWidgetProtocol.UNWATCH) {
                        if (clients.unwatch(reply, session) && !clients.hasClients()) {
                            stopWatching();
                        }
                    } else if (what == HeadlightWidgetProtocol.WATCH) {
                        clients.watch(reply, session);
                        if (subscription == null) {
                            preferences.registerOnSharedPreferenceChangeListener(
                                    preferenceListener);
                            subscription =
                                    vehicle.subscribe(
                                            CanBusEventRouter.INTEREST_CONNECTION
                                                    | CanBusEventRouter.INTEREST_VEHICLE_STATE
                                                    | CanBusEventRouter.INTEREST_LIGHT_STATUS,
                                            new int[] {SWITCH_POSITION.stableId},
                                            worker,
                                            this::onEvent);
                        }
                        refresh();
                        publish();
                    } else if (what == HeadlightWidgetProtocol.SELECT && current) {
                        if (mode == HeadlightWidgetProtocol.REAR_FOG) toggleFog();
                        else select(mode);
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
            fog.observed(null);
            worker.removeCallbacks(fogTimeout);
            worker.removeCallbacks(timeout);
        } else if (event.kind == CanBusEvent.Kind.CONNECTION) {
            refresh();
        } else if (event.kind == CanBusEvent.Kind.LIGHT_STATUS) {
            fog.observed(event.rearFog);
            if (fog.pending < 0) worker.removeCallbacks(fogTimeout);
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
        refreshFog();
    }

    private void refreshFog() {
        fog.observed(vehicle.session(Collections.emptyList(), session -> session.readRearFog()));
        if (fog.pending < 0) worker.removeCallbacks(fogTimeout);
    }

    private void toggleFog() {
        if (!fog.toggle()) {
            publish();
            return;
        }
        publish();
        String error = RearFogController.execute(context, "rear_fog:toggle");
        refreshFog();
        fog.sent(error);
        if (fog.pending >= 0) worker.postDelayed(fogTimeout, CONFIRMATION_TIMEOUT_MS);
        publish();
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
        data.putInt(HeadlightWidgetProtocol.FOG_STATE, fog.value);
        data.putInt(HeadlightWidgetProtocol.FOG_PENDING, fog.pending);
        data.putBoolean(HeadlightWidgetProtocol.FOG_AVAILABLE, fog.available());
        data.putString(HeadlightWidgetProtocol.FOG_MESSAGE, fog.message);
        data.putInt(HeadlightWidgetProtocol.PENDING, state.pending);
        data.putBoolean(HeadlightWidgetProtocol.AVAILABLE, state.available() && !commandInFlight);
        data.putString(
                HeadlightWidgetProtocol.MESSAGE,
                automatic && state.message.isEmpty() ? "Автосвет" : state.message);
        if (!clients.publish(HeadlightWidgetProtocol.STATE, data)) {
            stopWatching();
        }
    }

    private void stopWatching() {
        ++generation;
        worker.removeCallbacks(fogTimeout);
        worker.removeCallbacks(timeout);
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        clients.clear();
        switchPosition = -1;
        fog.observed(null);
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
