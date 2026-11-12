package ru.big.town.anative;

import java.util.function.LongSupplier;

/** Live door/Drive transitions for Apollo, independent of drive-mode restoration settings. */
final class ApolloDriveRestoreController {
    private final Runnable apply;
    private final LongSupplier wakeGeneration;
    private ModeRestoreTriggers triggers = new ModeRestoreTriggers();
    private long observedWakeGeneration = Long.MIN_VALUE;

    ApolloDriveRestoreController(Runnable apply, LongSupplier wakeGeneration) {
        this.apply = apply;
        this.wakeGeneration = wakeGeneration;
    }

    void onEvent(CanBusEvent event) {
        long currentWakeGeneration = wakeGeneration.getAsLong();
        if (currentWakeGeneration != observedWakeGeneration) {
            triggers = new ModeRestoreTriggers();
            observedWakeGeneration = currentWakeGeneration;
        }
        if (event.kind == CanBusEvent.Kind.CONNECTION
                || event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            triggers = new ModeRestoreTriggers();
            return;
        }
        if (event.origin != CanBusEvent.Origin.LIVE) {
            return;
        }
        if (event.kind == CanBusEvent.Kind.DOOR) {
            triggers.onDoor(event.first);
        } else if (event.kind == CanBusEvent.Kind.GEAR && triggers.onGear(event.first)) {
            apply.run();
        }
    }
}
