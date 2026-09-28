package ru.big.town.anative;

import java.util.function.BooleanSupplier;

/** Bounded, feedback-driven SREV selection followed by a single charge-target write. */
final class SaveChargeSequence {
    enum Outcome { CONFIRMED, MODE_UNCONFIRMED, TARGET_UNCONFIRMED, UNAVAILABLE, SEND_FAILED, CANCELLED }

    static final class State {
        final int mode, level;
        State(int mode, int level) { this.mode = mode; this.level = level; }
    }

    static final class Result {
        final Outcome outcome;
        final boolean modeConfirmed;
        final State observed;
        Result(Outcome outcome, boolean modeConfirmed, State observed) {
            this.outcome = outcome; this.modeConfirmed = modeConfirmed; this.observed = observed;
        }
    }

    interface Vehicle {
        State read();
        boolean selectSrev();
        boolean setLevel(int level);
        void modeConfirmed();
    }

    interface Clock {
        long now();
        void sleep(long milliseconds) throws InterruptedException;
    }

    static Result run(int percent, Vehicle vehicle, Clock clock, BooleanSupplier active) {
        int level = VehicleRestorePolicy.requireSaveChargeLevel(percent);
        boolean modeConfirmed = false;
        State state = null;
        try {
            if (!active.getAsBoolean()) return result(Outcome.CANCELLED, false, null);
            state = vehicle.read();
            if (!active.getAsBoolean()) return result(Outcome.CANCELLED, false, state);
            if (state == null) return result(Outcome.UNAVAILABLE, false, null);
            if (state.mode != VehicleRestorePolicy.SOC_SREV) {
                if (!vehicle.selectSrev()) return result(Outcome.SEND_FAILED, false, state);
                long until = clock.now() + 2000;
                do {
                    clock.sleep(100);
                    if (!active.getAsBoolean()) return result(Outcome.CANCELLED, false, state);
                    state = vehicle.read();
                    if (!active.getAsBoolean()) return result(Outcome.CANCELLED, false, state);
                    if (state == null) return result(Outcome.UNAVAILABLE, false, null);
                    if (state.mode == VehicleRestorePolicy.SOC_SREV) break;
                } while (clock.now() < until);
                if (state.mode != VehicleRestorePolicy.SOC_SREV)
                    return result(Outcome.MODE_UNCONFIRMED, false, state);
            }
            modeConfirmed = true;
            // Preserve the actual mode even if the following target change fails.
            vehicle.modeConfirmed();
            if (!active.getAsBoolean()) return result(Outcome.CANCELLED, true, state);
            if (state.level == level) return result(Outcome.CONFIRMED, true, state);
            if (!vehicle.setLevel(level)) return result(Outcome.SEND_FAILED, true, state);
            long until = clock.now() + 3000;
            do {
                clock.sleep(100);
                if (!active.getAsBoolean()) return result(Outcome.CANCELLED, true, state);
                state = vehicle.read();
                if (!active.getAsBoolean()) return result(Outcome.CANCELLED, true, state);
                if (state == null) return result(Outcome.UNAVAILABLE, true, null);
                // Another input may have selected EV while this request was waiting.
                if (state.mode != VehicleRestorePolicy.SOC_SREV)
                    return result(Outcome.MODE_UNCONFIRMED, true, state);
                if (state.level == level) return result(Outcome.CONFIRMED, true, state);
            } while (clock.now() < until);
            return result(Outcome.TARGET_UNCONFIRMED, true, state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(Outcome.CANCELLED, modeConfirmed, state);
        }
    }

    private static Result result(Outcome outcome, boolean modeConfirmed, State state) {
        return new Result(outcome, modeConfirmed, state);
    }
}
