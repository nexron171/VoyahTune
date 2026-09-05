package ru.big.town.anative;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed, process-wide driver-door state source. It contains no CAN transport knowledge. */
final class DriverDoorStateController {
    enum Source {
        LIVE,
        SNAPSHOT,
        REPLAY
    }

    static final class State {
        final int frontLeft;
        final Source source;

        State(int frontLeft, Source source) {
            this.frontLeft = frontLeft;
            this.source = source;
        }

        boolean isLive() {
            return source == Source.LIVE;
        }
    }

    interface Listener {
        void onDriverDoorChanged(State state);
    }

    private final Handler serialHandler;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile int currentFrontLeft = -1;

    DriverDoorStateController(Handler serialHandler) {
        this.serialHandler = serialHandler;
    }

    Subscription subscribe(Handler deliveryHandler, Listener listener) {
        if (deliveryHandler == null || listener == null) {
            throw new IllegalArgumentException("deliveryHandler/listener required");
        }
        Registration registration = new Registration(deliveryHandler, listener);
        serialHandler.post(() -> {
            if (!registration.active.get()) return;
            registrations.add(registration);
            int snapshot = currentFrontLeft;
            if (snapshot >= 0) registration.deliver(new State(snapshot, Source.REPLAY));
        });
        return new Subscription(this, registration);
    }

    int currentFrontLeft() {
        return currentFrontLeft;
    }

    void reset() {
        currentFrontLeft = -1;
    }

    void accept(int frontLeft, Source source) {
        if (frontLeft < 0) return;
        currentFrontLeft = frontLeft;
        State state = new State(frontLeft, source);
        for (Registration registration : registrations) registration.deliver(state);
    }

    private void remove(Registration registration) {
        if (!registration.active.compareAndSet(true, false)) return;
        serialHandler.post(() -> registrations.remove(registration));
    }

    static final class Subscription implements AutoCloseable {
        private final DriverDoorStateController owner;
        private final Registration registration;

        Subscription(DriverDoorStateController owner, Registration registration) {
            this.owner = owner;
            this.registration = registration;
        }

        @Override
        public void close() {
            owner.remove(registration);
        }
    }

    private static final class Registration {
        final Handler deliveryHandler;
        final Listener listener;
        final AtomicBoolean active = new AtomicBoolean(true);

        Registration(Handler deliveryHandler, Listener listener) {
            this.deliveryHandler = deliveryHandler;
            this.listener = listener;
        }

        void deliver(State state) {
            deliveryHandler.post(() -> {
                if (active.get()) listener.onDriverDoorChanged(state);
            });
        }
    }
}
