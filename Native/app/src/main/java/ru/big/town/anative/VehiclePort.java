package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/** Vehicle requests and asynchronous observations share one replaceable boundary. */
interface VehiclePort {
    interface Subscription { void close(); }
    Subscription subscribe(int interests, int[] stateIds, Handler handler, Consumer<CanBusEvent> listener);
    void requestEnergySnapshot();
    <T> T session(Collection<OemVehicleStateTransport.StateKey> keys,
                  OemVehicleStateTransport.SessionOperation<T> operation);
    Map<OemVehicleStateTransport.StateKey, Integer> driveProfile(String mode);

    static VehiclePort oem(Context context) {
        Context app = context.getApplicationContext();
        return new VehiclePort() {
            @Override public Subscription subscribe(int interests, int[] ids, Handler handler, Consumer<CanBusEvent> listener) {
                CanBusEventHub.Subscription subscription = CanBusEventHub.get(app)
                        .subscribe(interests, ids, handler, listener::accept);
                return subscription::close;
            }
            @Override public void requestEnergySnapshot() { CanBusEventHub.get(app).requestEnergySnapshot(); }
            @Override public <T> T session(Collection<OemVehicleStateTransport.StateKey> keys,
                                           OemVehicleStateTransport.SessionOperation<T> operation) {
                return OemVehicleStateTransport.withSession(app, keys, operation);
            }
            @Override public Map<OemVehicleStateTransport.StateKey, Integer> driveProfile(String mode) {
                return DriveModeCanTransport.statesFor(app, mode);
            }
        };
    }
}
