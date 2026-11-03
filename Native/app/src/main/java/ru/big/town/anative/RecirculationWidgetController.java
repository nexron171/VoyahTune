package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.RecirculationWidgetProtocol;

final class RecirculationWidgetController {
    private final Context context;
    private final CanBusEventHub hub;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WidgetClients clients = new WidgetClients();
    private final RecirculationWidgetState state = new RecirculationWidgetState();
    private CanBusEventHub.Subscription subscription;
    private volatile long generation;
    private volatile boolean closed;
    private long sampledAt;
    private final Runnable poll = this::poll;

    RecirculationWidgetController(Context context) {
        this.context = context.getApplicationContext();
        hub = CanBusEventHub.get(context);
    }

    void handle(Message request) {
        if (closed || request.replyTo == null) return;
        if (request.what == RecirculationWidgetProtocol.UNWATCH) {
            if (clients.unwatch(request.replyTo, request.arg2) && !clients.hasClients())
                stopWatching();
        } else if (request.what == RecirculationWidgetProtocol.WATCH) {
            clients.watch(request.replyTo, request.arg2);
            if (subscription == null) {
                subscription =
                        hub.subscribe(
                                CanBusEventRouter.INTEREST_CONNECTION
                                        | CanBusEventRouter.INTEREST_CLIMATE,
                                new int[0],
                                handler,
                                this::onEvent);
                handler.post(poll);
            }
            publish();
        } else if (request.what == RecirculationWidgetProtocol.CYCLE
                && clients.isWatching(request.replyTo, request.arg2)) {
            cycle();
        }
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) return;
        if (event.kind == CanBusEvent.Kind.CLIMATE) {
            if (event.elapsedRealtime < sampledAt) return;
            sampledAt = event.elapsedRealtime;
            state.observe(RecirculationWidgetOperation.observedMode(event.climate));
        } else {
            ++generation;
            sampledAt = 0;
            state.reset("Нет связи с автомобилем");
            if (event.kind == CanBusEvent.Kind.CONNECTION) hub.requestClimateSnapshot();
        }
        publish();
    }

    private void poll() {
        if (closed || !clients.hasClients()) {
            stopWatching();
            return;
        }
        if (sampledAt > 0 && SystemClock.elapsedRealtime() - sampledAt > 5000) {
            ++generation;
            sampledAt = 0;
            state.reset("Нет свежих данных рециркуляции");
            publish();
        }
        hub.requestClimateSnapshot();
        handler.postDelayed(poll, 1000);
    }

    private void cycle() {
        if (!state.begin()) {
            publish();
            return;
        }
        long expectedGeneration = generation;
        long deadline = SystemClock.elapsedRealtime() + 3000;
        int[] result = {CarPlayClimateProtocol.UNAVAILABLE};
        OemClimateState[] latest = {null};
        long[] sampled = {0};
        publish();
        ApplyEngine.postIndependentUserCommand(
                "recirculation widget",
                () ->
                        result[0] =
                                RecirculationWidgetOperation.cycle(
                                        context,
                                        deadline,
                                        () -> !closed && generation == expectedGeneration,
                                        latest,
                                        sampled),
                () ->
                        handler.post(
                                () -> {
                                    if (closed || generation != expectedGeneration) return;
                                    if (latest[0] != null && sampled[0] >= sampledAt) {
                                        sampledAt = sampled[0];
                                        state.observe(
                                                RecirculationWidgetOperation.observedMode(
                                                        latest[0]));
                                    }
                                    state.complete(result[0]);
                                    publish();
                                    hub.requestClimateSnapshot();
                                }));
    }

    private void publish() {
        Bundle data = new Bundle();
        data.putInt(RecirculationWidgetProtocol.MODE, state.mode);
        data.putBoolean(RecirculationWidgetProtocol.AVAILABLE, state.available());
        data.putBoolean(RecirculationWidgetProtocol.PENDING, state.pending);
        data.putString(RecirculationWidgetProtocol.MESSAGE, state.message);
        if (!clients.publish(RecirculationWidgetProtocol.STATE, data)) stopWatching();
    }

    private void stopWatching() {
        ++generation;
        handler.removeCallbacks(poll);
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        clients.clear();
        sampledAt = 0;
        state.reset("Нет данных рециркуляции");
    }

    void close() {
        closed = true;
        stopWatching();
    }
}
