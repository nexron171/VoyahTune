package ru.big.town.anative;

import android.content.Context;
import java.util.Collections;

/** One-shot cap opening in Parking. Never unlocks the charging connector or restores the request. */
final class PortCapController {
    enum Outcome { ACCEPTED, NOT_IN_PARK, STATE_UNAVAILABLE, TRANSPORT_FAILURE }

    static final class OpenResult {
        final Outcome outcome;
        final Integer refillLiters;

        OpenResult(Outcome outcome, Integer refillLiters) {
            this.outcome = outcome;
            this.refillLiters = refillLiters;
        }
    }

    private static OpenResult result(Outcome outcome) { return new OpenResult(outcome, null); }

    private static OemVehicleStateTransport.StateKey keyFor(String action) {
        if ("port_cap:fuel".equals(action)) {
            return new OemVehicleStateTransport.StateKey("IVI_FUEL_PORT_CAP", 778);
        }
        if ("port_cap:charge".equals(action)) {
            return new OemVehicleStateTransport.StateKey("IVI_CHRG_PORT_CAP", 779);
        }
        return null;
    }

    static OpenResult open(Context context, String action) {
        OemVehicleStateTransport.StateKey key = keyFor(action);
        if (key == null) return result(Outcome.TRANSPORT_FAILURE);
        OpenResult result = OemVehicleStateTransport.withSession(context, Collections.singleton(key),
                session -> open(session, action));
        return result == null ? result(Outcome.TRANSPORT_FAILURE) : result;
    }

    /** Read fresh gear and write the single cap request under the same OEM transaction lock. */
    static OpenResult open(OemVehicleStateTransport.Session session, String action) {
        OemVehicleStateTransport.StateKey key = keyFor(action);
        if (key == null) return result(Outcome.TRANSPORT_FAILURE);
        OemVehicleStateTransport.GearStatus gear = session.readGearStatus();
        if (gear == null) return result(Outcome.STATE_UNAVAILABLE);
        // OEM GearState.Parking carries both ordinal=0 and value=0 in its Parcelable.
        if (gear.ordinal != 0 || gear.value != 0) return result(Outcome.NOT_IN_PARK);
        if (!session.sendVehicleState(new OemVehicleStateTransport.StateValue(key, 1),
                "voice open " + key.name).accepted()) return result(Outcome.TRANSPORT_FAILURE);
        if ("port_cap:fuel".equals(action)) {
            try {
                OemVehicleStateTransport.FuelLevel fuel = session.readFuelLevel();
                if (fuel != null) return new OpenResult(Outcome.ACCEPTED,
                        FuelRefillEstimate.liters(fuel.capacityLiters, fuel.remainingPercent));
            } catch (RuntimeException ignored) {
                // A missing fuel estimate does not undo the already accepted cap request.
            }
        }
        return result(Outcome.ACCEPTED);
    }
}
