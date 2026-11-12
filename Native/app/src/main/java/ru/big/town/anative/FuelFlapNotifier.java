package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Collections;

/** Watches the shared OEM event stream even when the fuel widget is not visible. */
final class FuelFlapNotifier implements AutoCloseable {
    private final Context context;
    private final VehiclePort vehicle;
    private final HandlerThread thread = new HandlerThread("FuelFlapNotice");
    private final FuelFlapOpeningPolicy policy = new FuelFlapOpeningPolicy();
    private final VehiclePort.Subscription subscription;
    private volatile boolean closed;

    FuelFlapNotifier(Context context) {
        this.context = context.getApplicationContext();
        vehicle = VehiclePort.oem(context);
        thread.start();
        subscription =
                vehicle.subscribe(
                        CanBusEventRouter.INTEREST_CONNECTION
                                | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                        new int[] {FuelFlapOpeningPolicy.STATE_ID},
                        new Handler(thread.getLooper()),
                        this::onEvent);
    }

    private void onEvent(CanBusEvent event) {
        if (closed || !policy.accept(event)) return;
        Integer liters =
                vehicle.session(
                        Collections.emptyList(),
                        session -> {
                            OemVehicleStateTransport.FuelLevel fuel = session.readFuelLevel();
                            return fuel == null
                                    ? null
                                    : FuelRefillEstimate.liters(
                                            fuel.capacityLiters, fuel.remainingPercent);
                        });
        if (!closed) OemToastNotifier.showFuelFlap(context, FuelFlapOpeningPolicy.message(liters));
    }

    @Override
    public void close() {
        closed = true;
        subscription.close();
        thread.quitSafely();
    }
}
