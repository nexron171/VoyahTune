package ru.big.town.anative;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed, process-wide gear state source. It contains no CAN transport knowledge. */
final class GearStateController {
    interface Listener {
        void onGearChanged(int gearValue);
    }

    private final Handler serialHandler;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile int currentGear = -1;

    GearStateController(Handler serialHandler) {
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
            int snapshot = currentGear;
            if (snapshot >= 0) registration.deliver(snapshot);
        });
        return new Subscription(this, registration);
    }

    int currentGear() {
        return currentGear;
    }

    void reset() {
        currentGear = -1;
    }

    void accept(int gearValue) {
        if (gearValue < 0 || gearValue == currentGear) return;
        currentGear = gearValue;
        for (Registration registration : registrations) registration.deliver(gearValue);
    }

    private void remove(Registration registration) {
        if (!registration.active.compareAndSet(true, false)) return;
        serialHandler.post(() -> registrations.remove(registration));
    }

    static final class Subscription implements AutoCloseable {
        private final GearStateController owner;
        private final Registration registration;

        Subscription(GearStateController owner, Registration registration) {
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

        void deliver(int gearValue) {
            deliveryHandler.post(() -> {
                if (active.get()) listener.onGearChanged(gearValue);
            });
        }
    }
}
