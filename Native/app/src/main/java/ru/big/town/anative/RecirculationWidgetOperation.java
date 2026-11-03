package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;

import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.RecirculationWidgetProtocol;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class RecirculationWidgetOperation {
    private RecirculationWidgetOperation() {}

    static int observedMode(OemClimateState state) {
        if (state == null) return RecirculationWidgetProtocol.UNKNOWN;
        switch (state.circulation) {
            case OemClimateState.CIRCULATION_INTERNAL:
                return RecirculationWidgetProtocol.ON;
            case OemClimateState.CIRCULATION_EXTERNAL:
                return RecirculationWidgetProtocol.OFF;
            case OemClimateState.CIRCULATION_AUTOMATIC:
                return RecirculationWidgetProtocol.AUTO;
            default:
                return RecirculationWidgetProtocol.UNKNOWN;
        }
    }

    static int cycle(
            Context context,
            long deadline,
            BooleanSupplier active,
            OemClimateState[] latest,
            long[] sampled) {
        if (!active.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        return cycle(
                OemVehicleStateTransport.openClimateSession(context),
                deadline,
                active,
                latest,
                sampled,
                SystemClock::elapsedRealtime,
                Thread::sleep);
    }

    static int cycle(
            OemVehicleStateTransport.ClimateSession session,
            long deadline,
            BooleanSupplier active,
            OemClimateState[] latest,
            long[] sampled,
            LongSupplier clock,
            ClimateControlOperation.Pause pause) {
        if (!active.getAsBoolean() || clock.getAsLong() >= deadline) {
            return CarPlayClimateProtocol.TIMEOUT;
        }
        if (session == null) return CarPlayClimateProtocol.UNAVAILABLE;
        OemClimateState before = session.read();
        latest[0] = before;
        sampled[0] = clock.getAsLong();
        int target = RecirculationWidgetProtocol.nextMode(observedMode(before));
        if (target == RecirculationWidgetProtocol.UNKNOWN)
            return CarPlayClimateProtocol.UNAVAILABLE;
        // The widget order differs from the OEM pulse order; use confirmed absolute targets.
        String field =
                target == RecirculationWidgetProtocol.AUTO
                        ? CarPlayClimateProtocol.RECIRCULATION_AUTO
                        : CarPlayClimateProtocol.RECIRCULATION;
        double value = target == RecirculationWidgetProtocol.OFF ? 0 : 1;
        return ClimateControlOperation.apply(
                session, field, value, deadline, active, latest, sampled, clock, pause);
    }
}
