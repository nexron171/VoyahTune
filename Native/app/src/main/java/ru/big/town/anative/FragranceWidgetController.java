package ru.big.town.anative;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;

import ru.big.town.common.FragranceWidgetProtocol;
import ru.big.town.common.FragranceWidgetProtocol.Snapshot;

final class FragranceWidgetController {
    private static final long POLL_MS = 5000;
    private final VehiclePort vehicle;
    private final WidgetClients clients = new WidgetClients();
    private final HandlerThread thread = new HandlerThread("FragranceWidget");
    private final Handler worker;
    private final Runnable refreshTask = this::refresh;
    private final Runnable eventRefresh = this::refresh;
    private VehiclePort.Subscription subscription;
    private volatile boolean closed;
    private int pendingCommand;
    private int pendingValue;
    private long deadline;

    FragranceWidgetController(Context context) {
        vehicle = VehiclePort.oem(context);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void handle(Message message) {
        Messenger client = message.replyTo;
        int command = message.what;
        int value = message.arg1;
        int session = message.arg2;
        if (client != null) worker.post(() -> handle(client, session, command, value));
    }

    private void handle(Messenger client, int session, int command, int value) {
        if (closed) return;
        if (command == FragranceWidgetProtocol.UNWATCH) {
            if (clients.unwatch(client, session) && !clients.hasClients()) stopWatching();
            return;
        }
        if (command == FragranceWidgetProtocol.WATCH) {
            clients.watch(client, session);
            if (subscription == null) {
                int[] ids = FragranceVehicle.KEYS.stream().mapToInt(key -> key.stableId).toArray();
                subscription =
                        vehicle.subscribe(
                                CanBusEventRouter.INTEREST_CONNECTION
                                        | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                                ids,
                                worker,
                                this::onEvent);
            }
            refresh();
            return;
        }
        if (!clients.isWatching(client, session)) return;
        if (pendingCommand == 0) {
            Boolean accepted =
                    vehicle.session(
                            FragranceVehicle.KEYS,
                            channel -> !closed && FragranceVehicle.apply(channel, command, value));
            if (!Boolean.TRUE.equals(accepted)) {
                sample("Автомобиль не принял команду");
                return;
            }
            pendingCommand = command;
            pendingValue = value;
            deadline = SystemClock.elapsedRealtime() + 3000;
        }
        refresh();
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            pendingCommand = 0;
            publish(Snapshot.UNKNOWN, "Нет связи с автомобилем");
        } else {
            // OEM callbacks may arrive separately for power and scent.
            if (!worker.hasCallbacks(eventRefresh)) worker.postDelayed(eventRefresh, 60);
        }
    }

    private void refresh() {
        sample("");
    }

    private void sample(String message) {
        if (closed || !clients.hasClients()) {
            stopWatching();
            return;
        }
        Snapshot snapshot = vehicle.session(FragranceVehicle.KEYS, FragranceVehicle::read);
        if (snapshot == null) snapshot = Snapshot.UNKNOWN;
        if (pendingCommand != 0) {
            if (snapshot.matches(pendingCommand, pendingValue)) {
                pendingCommand = 0;
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                pendingCommand = 0;
                message = "Изменение не подтверждено";
            }
        }
        publish(snapshot, message);
    }

    private void publish(Snapshot state, String message) {
        Bundle data = state.bundle();
        data.putBoolean(FragranceWidgetProtocol.BUSY, pendingCommand != 0);
        data.putString(FragranceWidgetProtocol.MESSAGE, message);
        worker.removeCallbacks(refreshTask);
        worker.removeCallbacks(eventRefresh);
        if (clients.publish(FragranceWidgetProtocol.STATE, data)) {
            worker.postDelayed(refreshTask, pendingCommand == 0 ? POLL_MS : 250);
        } else {
            stopWatching();
        }
    }

    private void stopWatching() {
        worker.removeCallbacks(refreshTask);
        worker.removeCallbacks(eventRefresh);
        pendingCommand = 0;
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
