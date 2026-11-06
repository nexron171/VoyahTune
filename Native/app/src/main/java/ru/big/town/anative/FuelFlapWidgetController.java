package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;

import ru.big.town.common.FuelFlapWidgetProtocol;

import java.util.Collections;

final class FuelFlapWidgetController {
    private static final long REFRESH_INTERVAL_MS = 10_000;
    private final Context context;
    private final VehiclePort vehicle;
    private final HandlerThread thread = new HandlerThread("FuelFlapWidget");
    private final Handler worker;
    private final WidgetClients clients = new WidgetClients();
    private VehiclePort.Subscription subscription;
    private volatile boolean closed;
    private final Runnable refreshTask = this::refresh;

    FuelFlapWidgetController(Context context) {
        this.context = context.getApplicationContext();
        vehicle = VehiclePort.oem(context);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void handle(Message request) {
        int what = request.what;
        int session = request.arg2;
        Messenger reply = request.replyTo;
        worker.post(
                () -> {
                    if (closed || reply == null) return;
                    if (what == FuelFlapWidgetProtocol.UNWATCH) {
                        if (clients.unwatch(reply, session) && !clients.hasClients())
                            stopWatching();
                    } else if (what == FuelFlapWidgetProtocol.WATCH) {
                        clients.watch(reply, session);
                        if (subscription == null) {
                            subscription =
                                    vehicle.subscribe(
                                            CanBusEventRouter.INTEREST_CONNECTION
                                                    | CanBusEventRouter.INTEREST_GEAR,
                                            new int[0],
                                            worker,
                                            this::onEvent);
                        }
                        refresh();
                    } else if (what == FuelFlapWidgetProtocol.OPEN
                            && clients.isWatching(reply, session)) {
                        open();
                    }
                });
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            worker.removeCallbacks(refreshTask);
            publish(new FuelFlapWidgetSnapshot(false, null, "Нет связи с автомобилем"));
        } else {
            refresh();
        }
    }

    private FuelFlapWidgetSnapshot read() {
        FuelFlapWidgetSnapshot snapshot =
                vehicle.session(Collections.emptyList(), FuelFlapWidgetSnapshot::read);
        return snapshot == null
                ? new FuelFlapWidgetSnapshot(false, null, "Нет связи с автомобилем")
                : snapshot;
    }

    private void refresh() {
        worker.removeCallbacks(refreshTask);
        if (closed || !clients.hasClients()) {
            stopWatching();
            return;
        }
        publish(read());
        if (clients.hasClients()) worker.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }

    private void open() {
        PortCapController.OpenResult result = PortCapController.open(context, "port_cap:fuel");
        FuelFlapWidgetSnapshot snapshot = read();
        String message;
        switch (result.outcome) {
            case ACCEPTED:
                message = "Команда открытия отправлена";
                break;
            case NOT_IN_PARK:
                message = "Переведите селектор в P";
                break;
            case STATE_UNAVAILABLE:
                message = "Не удалось проверить положение селектора";
                break;
            default:
                message = "Не удалось отправить команду открытия";
                break;
        }
        publish(
                snapshot,
                message,
                snapshot.refillLiters != null ? snapshot.refillLiters : result.refillLiters);
    }

    private void publish(FuelFlapWidgetSnapshot snapshot) {
        publish(snapshot, snapshot.message, snapshot.refillLiters);
    }

    private void publish(FuelFlapWidgetSnapshot snapshot, String message, Integer refillLiters) {
        Bundle state = new Bundle();
        state.putBoolean(FuelFlapWidgetProtocol.CAN_OPEN, snapshot.canOpen);
        if (refillLiters != null) {
            state.putInt(FuelFlapWidgetProtocol.REFILL_LITERS, refillLiters);
        }
        if (snapshot.fuel != null) {
            state.putInt(FuelFlapWidgetProtocol.CAPACITY_LITERS, snapshot.fuel.capacityLiters);
            state.putFloat(
                    FuelFlapWidgetProtocol.REMAINING_PERCENT, snapshot.fuel.remainingPercent);
        }
        state.putString(FuelFlapWidgetProtocol.MESSAGE, message);
        if (!clients.publish(FuelFlapWidgetProtocol.STATE, state)) stopWatching();
    }

    private void stopWatching() {
        worker.removeCallbacks(refreshTask);
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        clients.clear();
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
