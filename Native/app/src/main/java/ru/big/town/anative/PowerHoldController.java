package ru.big.town.anative;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-shot stock-style Power Hold request with exact P and SOC preconditions. */
final class PowerHoldController {
    private static final String TAG = "PowerHoldController";

    private static final OemVehicleStateTransport.StateKey BMS_SOC_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.BMS_SOC_DISPLAY, PowerHoldPolicy.BMS_SOC_DISPLAY_ID);
    private static final OemVehicleStateTransport.StateKey EXTENDER_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.SCENE_MODE_EXTENDER_SET,
                    PowerHoldPolicy.SCENE_MODE_EXTENDER_SET_ID);
    private static final OemVehicleStateTransport.StateKey SWITCH_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.POWER_HOLD_MODE_SWITCH,
                    PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID);
    private static final OemVehicleStateTransport.StateKey TIME_KEY =
            new OemVehicleStateTransport.StateKey(
                    PowerHoldPolicy.POWER_HOLD_MODE_TIME,
                    PowerHoldPolicy.POWER_HOLD_MODE_TIME_ID);

    static final class Gear {
        final int ordinal;
        final int value;

        Gear(int ordinal, int value) {
            this.ordinal = ordinal;
            this.value = value;
        }
    }

    interface Session {
        Gear readGear();

        Integer readSoc();

        boolean sendActivation(Map<String, Integer> values, String label);
    }

    interface SessionAction {
        PowerHoldPolicy.Outcome run(Session session);
    }

    interface VehicleGateway {
        PowerHoldPolicy.Outcome runActivation(SessionAction action);
    }

    private final VehicleGateway gateway;

    static PowerHoldController create(Context context) {
        Context app = context.getApplicationContext();
        return new PowerHoldController(action -> {
            PowerHoldPolicy.Outcome result = OemVehicleStateTransport.withSession(
                    app, Arrays.asList(BMS_SOC_KEY, EXTENDER_KEY, SWITCH_KEY, TIME_KEY),
                    oemSession -> action.run(new Session() {
                        @Override
                        public Gear readGear() {
                            OemVehicleStateTransport.GearStatus status =
                                    oemSession.readGearStatus();
                            return status == null
                                    ? null : new Gear(status.ordinal, status.value);
                        }

                        @Override
                        public Integer readSoc() {
                            return oemSession.readVehicleState(BMS_SOC_KEY);
                        }

                        @Override
                        public boolean sendActivation(Map<String, Integer> values,
                                                      String label) {
                            LinkedHashMap<OemVehicleStateTransport.StateKey, Integer> keyed =
                                    new LinkedHashMap<>();
                            keyed.put(TIME_KEY, values.get(PowerHoldPolicy.POWER_HOLD_MODE_TIME));
                            keyed.put(EXTENDER_KEY,
                                    values.get(PowerHoldPolicy.SCENE_MODE_EXTENDER_SET));
                            keyed.put(SWITCH_KEY,
                                    values.get(PowerHoldPolicy.POWER_HOLD_MODE_SWITCH));
                            return oemSession.sendBundle(keyed, label).accepted();
                        }
                    }));
            return result == null ? PowerHoldPolicy.Outcome.TRANSPORT_FAILURE : result;
        });
    }

    PowerHoldController(VehicleGateway gateway) {
        if (gateway == null) throw new IllegalArgumentException("Power Hold gateway is null");
        this.gateway = gateway;
    }

    PowerHoldPolicy.Outcome activate() {
        try {
            PowerHoldPolicy.Outcome outcome = gateway.runActivation(session -> {
                Gear gear = session.readGear();
                if (gear == null) return PowerHoldPolicy.Outcome.STATE_UNAVAILABLE;
                if (!PowerHoldPolicy.isParking(gear.ordinal, gear.value)) {
                    return PowerHoldPolicy.Outcome.NOT_IN_PARK;
                }

                Integer soc = session.readSoc();
                if (soc == null) return PowerHoldPolicy.Outcome.STATE_UNAVAILABLE;
                PowerHoldPolicy.Outcome validation =
                        PowerHoldPolicy.validate(gear.ordinal, gear.value, soc);
                if (validation != PowerHoldPolicy.Outcome.ACCEPTED) return validation;

                return session.sendActivation(
                        PowerHoldPolicy.activationValues(), "power hold activate")
                        ? PowerHoldPolicy.Outcome.ACCEPTED
                        : PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
            });
            return outcome == null ? PowerHoldPolicy.Outcome.TRANSPORT_FAILURE : outcome;
        } catch (RuntimeException e) {
            try {
                Log.e(TAG, "Power Hold activation failed", e);
            } catch (RuntimeException ignored) {
                // Local JVM tests do not provide android.util.Log.
            }
            return PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
        }
    }
}
