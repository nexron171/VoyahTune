package ru.big.town.anative;

/** Main-thread owner of early and screen-dependent work within one physical wake. */
final class PhysicalWakeActions {
    private enum Phase {
        ASLEEP,
        WAITING_FOR_SCREEN,
        READY
    }

    private final Runnable earlyActions;
    private final Runnable screenActions;
    private Phase phase = Phase.ASLEEP;

    PhysicalWakeActions(Runnable earlyActions, Runnable screenActions) {
        this.earlyActions = earlyActions;
        this.screenActions = screenActions;
    }

    void onWake(boolean screenReady) {
        if (phase == Phase.ASLEEP) {
            phase = Phase.WAITING_FOR_SCREEN;
            earlyActions.run();
        }
        if (screenReady) {
            onScreenOn();
        }
    }

    void onScreenOn() {
        if (phase != Phase.WAITING_FOR_SCREEN) {
            return;
        }
        phase = Phase.READY;
        screenActions.run();
    }

    void onSleep() {
        phase = Phase.ASLEEP;
    }
}
