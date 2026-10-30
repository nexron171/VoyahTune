package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Shared feedback-driven HVAC operation for CarPlay, voice and scenarios. */
final class ClimateControlOperation {
    private ClimateControlOperation() {}

    interface Pause {
        void sleep(long milliseconds) throws InterruptedException;
    }

    static int apply(
            Context context,
            String field,
            double target,
            long deadline,
            BooleanSupplier active,
            OemClimateState[] latest,
            long[] sampled) {
        if (!(active.getAsBoolean() && SystemClock.elapsedRealtime() < deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        OemVehicleStateTransport.ClimateSession session =
                OemVehicleStateTransport.openClimateSession(context);
        if (session == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        return apply(
                session,
                field,
                target,
                deadline,
                active,
                latest,
                sampled,
                SystemClock::elapsedRealtime,
                Thread::sleep);
    }

    static int apply(
            OemVehicleStateTransport.ClimateSession session,
            String field,
            double target,
            long deadline,
            BooleanSupplier active,
            OemClimateState[] latest,
            long[] sampled,
            LongSupplier clock,
            Pause pause) {
        OemClimateState before = session.read();
        if (before == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        latest[0] = before;
        sampled[0] = clock.getAsLong();
        if (ClimateCommand.reached(before, field, target)) {
            return CarPlayClimateProtocol.SUCCESS;
        }
        ClimateCommand command = ClimateCommand.from(before, field, target);
        if (command == null) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        boolean circulationCycle = CarPlayClimateProtocol.CYCLE_RECIRCULATION.equals(field);
        int circulationTarget = circulationCycle ? ClimateCommand.nextCirculationMode(before) : -1;
        if (!(active.getAsBoolean() && clock.getAsLong() < deadline)) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        if (!session.send(command)) {
            return CarPlayClimateProtocol.UNAVAILABLE;
        }
        boolean circulationAdvanceAvailable =
                circulationCycle
                        || CarPlayClimateProtocol.RECIRCULATION.equals(field)
                        || CarPlayClimateProtocol.RECIRCULATION_AUTO.equals(field);
        long pollInterval = circulationAdvanceAvailable ? 500 : 100;
        while (active.getAsBoolean() && clock.getAsLong() < deadline) {
            try {
                // Allow the OEM pulse and its feedback to settle before another transition.
                pause.sleep(pollInterval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            OemClimateState observed = session.read();
            if (observed == null) {
                return CarPlayClimateProtocol.UNAVAILABLE;
            }
            latest[0] = observed;
            sampled[0] = clock.getAsLong();
            if (circulationCycle
                    ? observed.circulation == circulationTarget
                    : ClimateCommand.reached(observed, field, target)) {
                return CarPlayClimateProtocol.SUCCESS;
            }
            // A second pulse toward the target requires a confirmed first transition.
            if (circulationAdvanceAvailable
                    && ClimateCommand.circulationAdvanced(before, observed)) {
                circulationAdvanceAvailable = false;
                if (!(active.getAsBoolean() && clock.getAsLong() < deadline)) {
                    break;
                }
                if (!session.send(command)) {
                    return CarPlayClimateProtocol.UNAVAILABLE;
                }
            }
        }
        return CarPlayClimateProtocol.TIMEOUT;
    }
}
