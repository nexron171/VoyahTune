package ru.big.town.anative;

import android.content.Context;

import java.util.Collections;

/** One-shot stock-style manual wash request; no feedback subscription or restore ownership. */
final class WashModeController {
    private static final OemVehicleStateTransport.StateKey CLEANING_MODE_KEY =
            new OemVehicleStateTransport.StateKey(
                    WashModePolicy.CLEANING_MODE, WashModePolicy.CLEANING_MODE_ID);

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

        boolean sendCleaning(int value, String label);
    }

    interface SessionAction {
        WashModePolicy.Outcome run(Session session);
    }

    interface VehicleGateway {
        WashModePolicy.Outcome runActivation(SessionAction action);

        boolean sendCleaning(int value, String label);
    }

    private final VehicleGateway gateway;
    private final WashModeRequestLease lease;

    static WashModeController create(Context context) {
        Context app = context.getApplicationContext();
        return new WashModeController(new VehicleGateway() {
            @Override
            public WashModePolicy.Outcome runActivation(SessionAction action) {
                WashModePolicy.Outcome result = OemVehicleStateTransport.withSession(
                        app, Collections.singleton(CLEANING_MODE_KEY), oemSession ->
                                action.run(new Session() {
                                    @Override
                                    public Gear readGear() {
                                        OemVehicleStateTransport.GearStatus status =
                                                oemSession.readGearStatus();
                                        return status == null
                                                ? null
                                                : new Gear(status.ordinal, status.value);
                                    }

                                    @Override
                                    public boolean sendCleaning(int value, String label) {
                                        return oemSession.sendVehicleState(
                                                new OemVehicleStateTransport.StateValue(
                                                        CLEANING_MODE_KEY, value),
                                                label).accepted();
                                    }
                                }));
                return result == null ? WashModePolicy.Outcome.TRANSPORT_FAILURE : result;
            }

            @Override
            public boolean sendCleaning(int value, String label) {
                return OemVehicleStateTransport.sendVehicleState(
                        app, CLEANING_MODE_KEY, value, label).accepted();
            }
        }, WashModeRequestLease.from(app));
    }

    WashModeController(VehicleGateway gateway, WashModeRequestLease lease) {
        if (gateway == null) throw new IllegalArgumentException("Wash gateway is null");
        if (lease == null) throw new IllegalArgumentException("Wash lease is null");
        this.gateway = gateway;
        this.lease = lease;
    }

    WashModePolicy.Outcome activate() {
        return gateway.runActivation(session -> {
            Gear gear = session.readGear();
            if (gear == null) return WashModePolicy.Outcome.TRANSPORT_FAILURE;
            if (!WashModePolicy.isParking(gear.ordinal, gear.value)) {
                return WashModePolicy.Outcome.NOT_IN_PARK;
            }

            long generation = lease.arm();
            if (generation == 0L) return WashModePolicy.Outcome.TRANSPORT_FAILURE;
            if (!session.sendCleaning(WashModePolicy.CLEANING_ON, "wash mode activate")) {
                lease.disarm(generation);
                return WashModePolicy.Outcome.TRANSPORT_FAILURE;
            }
            return WashModePolicy.Outcome.ACCEPTED;
        });
    }

    boolean cleanupRequestBit(String reason) {
        long generation = lease.activeGeneration();
        if (generation == 0L) return true;
        if (!gateway.sendCleaning(
                WashModePolicy.CLEANING_OFF, "wash mode cleanup: " + reason)) {
            return false;
        }
        return lease.disarm(generation);
    }

    boolean hasArmedRequest() {
        return lease.activeGeneration() != 0L;
    }
}
