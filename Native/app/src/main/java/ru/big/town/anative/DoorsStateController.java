package ru.big.town.anative;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.big.town.common.ScenarioProtocol;

/**
 * Typed, process-wide состояние всех дверей (маска {@link CanBusEvent#DOOR_ALL}).
 *
 * <p>Дополняет {@link DriverDoorStateController}, который сохраняет прежнюю семантику только
 * водительской двери для TripStats/WiperCold. CAN-доступ не дублируется: оба контроллера
 * питаются от одной process-wide подписки {@link CanBusEventHub}.</p>
 */
final class DoorsStateController {
    interface Listener {
        void onDoorsChanged(int mask);
    }

    private final Handler serialHandler;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile int currentMask = -1;

    DoorsStateController(Handler serialHandler) {
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
            int snapshot = currentMask;
            if (snapshot >= 0) registration.deliver(snapshot);
        });
        return new Subscription(this, registration);
    }

    /** Последняя маска открытых дверей или -1, если состояние неизвестно. */
    int currentMask() {
        return currentMask;
    }

    /** Открыта ли конкретная дверь; при неизвестном состоянии всегда {@code false}. */
    boolean isOpen(String doorId) {
        int bit = bitFor(doorId);
        int mask = currentMask;
        return bit != 0 && mask >= 0 && (mask & bit) != 0;
    }

    void reset() {
        currentMask = -1;
    }

    /** Повтор той же маски не рассылается: гасит дребезг и даёт потребителям чистые рёбра. */
    void accept(int mask) {
        if (mask < 0 || mask == currentMask) return;
        currentMask = mask;
        for (Registration registration : registrations) registration.deliver(mask);
    }

    /** Бит двери по её идентификатору из {@link ScenarioProtocol}, или 0 для неизвестной двери. */
    static int bitFor(String doorId) {
        if (ScenarioProtocol.DOOR_DRIVER.equals(doorId)) return CanBusEvent.DOOR_DRIVER;
        if (ScenarioProtocol.DOOR_PASSENGER.equals(doorId)) return CanBusEvent.DOOR_PASSENGER;
        if (ScenarioProtocol.DOOR_REAR_LEFT.equals(doorId)) return CanBusEvent.DOOR_REAR_LEFT;
        if (ScenarioProtocol.DOOR_REAR_RIGHT.equals(doorId)) return CanBusEvent.DOOR_REAR_RIGHT;
        if (ScenarioProtocol.DOOR_BOOT.equals(doorId)) return CanBusEvent.DOOR_BOOT;
        if (ScenarioProtocol.DOOR_HOOD.equals(doorId)) return CanBusEvent.DOOR_HOOD;
        return 0;
    }

    private void remove(Registration registration) {
        if (!registration.active.compareAndSet(true, false)) return;
        serialHandler.post(() -> registrations.remove(registration));
    }

    static final class Subscription implements AutoCloseable {
        private final DoorsStateController owner;
        private final Registration registration;

        Subscription(DoorsStateController owner, Registration registration) {
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

        void deliver(int mask) {
            deliveryHandler.post(() -> {
                if (active.get()) listener.onDoorsChanged(mask);
            });
        }
    }
}
