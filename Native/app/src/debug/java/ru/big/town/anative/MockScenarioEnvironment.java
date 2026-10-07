package ru.big.town.anative;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import ru.big.town.common.ScenarioProtocol;

/** Uses real event controllers and OEM command encoding against the simulated car. */
final class MockScenarioEnvironment implements ScenarioEnvironment {
    private final MockVehicle vehicle;
    private final Consumer<Bundle> trace;
    private final ScenarioActionExecutor actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GearStateController gears = new GearStateController(main);
    private final DoorsStateController doors = new DoorsStateController(main);
    private final CopyOnWriteArrayList<IntConsumer> lightListeners = new CopyOnWriteArrayList<>();
    private final VehiclePort.Subscription subscription;
    private int light = 7, temperature = 12, minutes = 600, day = 1;
    private String drive = "COMFORT";

    MockScenarioEnvironment(SetModesService service, MockVehicle vehicle, Consumer<Bundle> trace) {
        this.vehicle = vehicle;
        this.trace = trace;
        actions = new ScenarioActionExecutor(service);
        subscription = vehicle.subscribe(CanBusEventRouter.INTEREST_GEAR | CanBusEventRouter.INTEREST_DOOR,
                new int[0], main, event -> {
                    if (event.kind == CanBusEvent.Kind.CONNECTION) {
                        gears.accept(vehicle.gear);
                        doors.accept(vehicle.doors);
                    } else if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
                        gears.reset(); doors.reset();
                    } else if (event.kind == CanBusEvent.Kind.GEAR) gears.accept(event.first);
                    else if (event.kind == CanBusEvent.Kind.DOOR) doors.accept(event.second);
                });
    }
    @Override public Subscription gear(Handler handler, IntConsumer listener) {
        GearStateController.Subscription registration = gears.subscribe(handler, listener::accept);
        return registration::close;
    }
    @Override public Subscription doors(Handler handler, IntConsumer listener) {
        DoorsStateController.Subscription registration = doors.subscribe(handler, listener::accept);
        return registration::close;
    }
    @Override public Subscription light(IntConsumer listener) {
        lightListeners.add(listener);
        listener.accept(light);
        event("scenarioLightSubscription", null, lightListeners.size());
        return () -> { lightListeners.remove(listener); event("scenarioLightSubscription", null, lightListeners.size()); };
    }
    void configure(Bundle data) {
        temperature = data.getInt("temperature", temperature);
        minutes = data.getInt("minutes", minutes);
        day = data.getInt("day", day);
        drive = data.getString("driveMode", drive);
        if (data.containsKey("light")) {
            light = data.getInt("light");
            for (IntConsumer listener : lightListeners) listener.accept(light);
        }
    }
    @Override public ScenarioPolicy.Snapshot snapshot(ScenarioDefinition scenario) {
        Integer soc = null;
        if (ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_SOC)) {
            OemVehicleStateTransport.StateKey key = new OemVehicleStateTransport.StateKey("BMS_SOC_DISPLAY", 615);
            soc = vehicle.session(Collections.singletonList(key), session -> session.readVehicleState(key));
        }
        event("scenarioSnapshot", scenario.id, soc == null ? -1 : soc);
        return new ScenarioPolicy.Snapshot(vehicle.connected ? light : -1,
                vehicle.connected ? temperature : ScenarioPolicy.Snapshot.UNKNOWN,
                soc == null ? -1 : soc, vehicle.connected ? drive : null, minutes, day);
    }
    @Override public void execute(String action, ScenarioActionExecutor.Completion completion) {
        event("scenarioAction", action, 0);
        ScenarioActionExecutor.Completion reply = (accepted, error) -> {
            event("scenarioActionResult", action, accepted ? 1 : 0);
            completion.onFinished(accepted, error);
        };
        if (SeatCommand.handles(action) || WindowCommand.handles(action)) actions.execute(action, reply);
        else reply.onFinished(vehicle.connected && vehicle.accepted, "Simulated platform operation");
    }
    @Override public void notifyUser(String text) { event("scenarioNotice", text, 0); }
    private void event(String op, String text, int value) {
        Bundle data = new Bundle(); data.putString("op", op); data.putString("text", text);
        data.putInt("value", value); data.putLong("elapsed", android.os.SystemClock.elapsedRealtime());
        trace.accept(data);
    }
    void close() { subscription.close(); lightListeners.clear(); }
}
