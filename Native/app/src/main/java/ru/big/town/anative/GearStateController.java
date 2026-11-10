package ru.big.town.anative;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed, process-wide gear state source. It contains no CAN transport knowledge. */
final class GearStateController {
    enum Source {
        LIVE,
        SNAPSHOT,
        REPLAY
    }

    static final class State {
        final int value;
        final Source source;

        State(int value, Source source) {
            this.value = value;
            this.source = source;
        }

        boolean isFresh() {
            return source != Source.REPLAY;
        }
    }

    interface Listener {
        void onGearChanged(int gearValue);
    }

    interface StateListener {
        void onGearChanged(State state);
    }

    private final Handler serialHandler;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile int currentGear = -1;

    GearStateController(Handler serialHandler) {
        this.serialHandler = serialHandler;
    }

    Subscription subscribe(Handler deliveryHandler, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener required");
        return subscribe(deliveryHandler, state -> listener.onGearChanged(state.value), false);
    }

    Subscription subscribeState(Handler deliveryHandler, StateListener listener) {
        return subscribe(deliveryHandler, listener, true);
    }

    private Subscription subscribe(
            Handler deliveryHandler, StateListener listener, boolean repeatFreshSamples) {
        if (deliveryHandler == null || listener == null) {
            throw new IllegalArgumentException("deliveryHandler/listener required");
        }
        Registration registration = new Registration(deliveryHandler, listener, repeatFreshSamples);
        serialHandler.post(() -> {
            if (!registration.active.get()) return;
            registrations.add(registration);
            int snapshot = currentGear;
            if (snapshot >= 0) registration.deliver(new State(snapshot, Source.REPLAY));
        });
        return new Subscription(this, registration);
    }

    int currentGear() {
        return currentGear;
    }

    void reset() {
        currentGear = -1;
    }

    void accept(int gearValue, Source source) {
        if (gearValue < 0 || gearValue > 4) return;
        boolean changed = gearValue != currentGear;
        currentGear = gearValue;
        State state = new State(gearValue, source);
        for (Registration registration : registrations) {
            if (changed || registration.repeatFreshSamples) registration.deliver(state);
        }
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
        final StateListener listener;
        final boolean repeatFreshSamples;
        final AtomicBoolean active = new AtomicBoolean(true);

        Registration(Handler deliveryHandler, StateListener listener, boolean repeatFreshSamples) {
            this.deliveryHandler = deliveryHandler;
            this.listener = listener;
            this.repeatFreshSamples = repeatFreshSamples;
        }

        void deliver(State state) {
            deliveryHandler.post(() -> {
                if (active.get()) listener.onGearChanged(state);
            });
        }
    }
}
