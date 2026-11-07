package ru.big.town.anative;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import ru.big.town.common.ScenarioProtocol;

import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/** Uses real event controllers and OEM command encoding against the simulated car. */
final class MockScenarioEnvironment implements ScenarioEnvironment {
    private final MockVehicle vehicle;
    private final Consumer<Bundle> trace;
    private final ScenarioActionExecutor actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GearStateController gears = new GearStateController(main);
    private final DoorsStateController doors = new DoorsStateController(main);
    private final CopyOnWriteArrayList<IntConsumer> lightListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> driveListeners = new CopyOnWriteArrayList<>();
    private final VehiclePort.Subscription subscription;
    private int light = 7;
    private int temperature = 12;
    private int minutes = 600;
    private int day = 1;
    private final CopyOnWriteArrayList<DoubleConsumer> cabinListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IntConsumer> speedListeners = new CopyOnWriteArrayList<>();
    private volatile double cabinTemperature = Double.NaN;
    private String drive = "COMFORT";

    MockScenarioEnvironment(SetModesService service, MockVehicle vehicle, Consumer<Bundle> trace) {
        this.vehicle = vehicle;
        this.trace = trace;
        actions = new ScenarioActionExecutor(service);
        subscription =
                vehicle.subscribe(
                        CanBusEventRouter.INTEREST_GEAR | CanBusEventRouter.INTEREST_DOOR,
                        new int[0],
                        main,
                        event -> {
                            if (event.kind == CanBusEvent.Kind.CONNECTION) {
                                gears.accept(vehicle.gear);
                                doors.accept(vehicle.doors);
                                for (Consumer<String> listener : driveListeners) {
                                    listener.accept(drive);
                                }
                                for (IntConsumer listener : speedListeners) {
                                    listener.accept(vehicle.speed);
                                }
                            } else if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
                                gears.reset();
                                doors.reset();
                                for (Consumer<String> listener : driveListeners) {
                                    listener.accept(null);
                                }
                                for (IntConsumer listener : speedListeners) {
                                    listener.accept(-1);
                                }
                            } else if (event.kind == CanBusEvent.Kind.GEAR) {
                                gears.accept(event.first);
                            } else if (event.kind == CanBusEvent.Kind.DOOR) {
                                doors.accept(event.second);
                            }
                        });
    }

    @Override
    public Subscription gear(Handler handler, IntConsumer listener) {
        GearStateController.Subscription registration = gears.subscribe(handler, listener::accept);
        return registration::close;
    }

    @Override
    public Subscription doors(Handler handler, IntConsumer listener) {
        DoorsStateController.Subscription registration = doors.subscribe(handler, listener::accept);
        return registration::close;
    }

    @Override
    public Subscription driveMode(Handler handler, Consumer<String> listener) {
        Consumer<String> delivery = value -> handler.post(() -> listener.accept(value));
        driveListeners.add(delivery);
        delivery.accept(vehicle.connected ? drive : null);
        return () -> driveListeners.remove(delivery);
    }

    @Override
    public Subscription cabinTemperature(Handler handler, DoubleConsumer listener) {
        DoubleConsumer delivery = value -> handler.post(() -> listener.accept(value));
        cabinListeners.add(delivery);
        return () -> cabinListeners.remove(delivery);
    }

    @Override
    public Subscription speed(Handler handler, IntConsumer listener) {
        IntConsumer delivery = value -> handler.post(() -> listener.accept(value));
        speedListeners.add(delivery);
        delivery.accept(vehicle.connected ? vehicle.speed : -1);
        return () -> speedListeners.remove(delivery);
    }

    @Override
    public Subscription light(IntConsumer listener) {
        lightListeners.add(listener);
        listener.accept(light);
        event("scenarioLightSubscription", null, lightListeners.size());
        return () -> {
            lightListeners.remove(listener);
            event("scenarioLightSubscription", null, lightListeners.size());
        };
    }

    void configure(Bundle data) {
        if (data.containsKey("speed")) {
            for (IntConsumer listener : speedListeners) {
                listener.accept(vehicle.connected ? vehicle.speed : -1);
            }
        }
        temperature = data.getInt("temperature", temperature);
        if (data.containsKey("cabinTemperature")) {
            double value = data.getDouble("cabinTemperature", Double.NaN);
            if (Double.isFinite(value)) {
                cabinTemperature = value;
                for (DoubleConsumer listener : cabinListeners) listener.accept(value);
            }
        }
        minutes = data.getInt("minutes", minutes);
        day = data.getInt("day", day);
        String selectedDrive = data.getString("driveMode", drive);
        if (!java.util.Objects.equals(drive, selectedDrive)) {
            drive = selectedDrive;
            for (Consumer<String> listener : driveListeners) {
                listener.accept(vehicle.connected ? drive : null);
            }
        }
        if (data.containsKey("light")) {
            light = data.getInt("light");
            for (IntConsumer listener : lightListeners) listener.accept(light);
        }
    }

    @Override
    public ScenarioPolicy.Snapshot snapshot(ScenarioDefinition scenario) {
        Integer soc = null;
        if (ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_SOC)) {
            OemVehicleStateTransport.StateKey key =
                    new OemVehicleStateTransport.StateKey("BMS_SOC_DISPLAY", 615);
            soc =
                    vehicle.session(
                            Collections.singletonList(key),
                            session -> session.readVehicleState(key));
        }
        event("scenarioSnapshot", scenario.id, soc == null ? -1 : soc);
        return new ScenarioPolicy.Snapshot(
                vehicle.connected ? light : -1,
                vehicle.connected ? temperature : ScenarioPolicy.Snapshot.UNKNOWN,
                soc == null ? -1 : soc,
                vehicle.connected ? drive : null,
                minutes,
                day,
                vehicle.connected ? gears.currentGear() : -1,
                cabinTemperature,
                vehicle.connected ? vehicle.speed : -1);
    }

    @Override
    public void execute(String action, ScenarioActionExecutor.Completion completion) {
        event("scenarioAction", action, 0);
        ScenarioActionExecutor.Completion reply =
                (accepted, error) -> {
                    event("scenarioActionResult", action, accepted ? 1 : 0);
                    completion.onFinished(accepted, error);
                };
        if (SeatCommand.handles(action)
                || WindowCommand.handles(action)
                || ru.big.town.common.WallpaperProtocol.handles(action)) {
            actions.execute(action, reply);
        } else {
            reply.onFinished(vehicle.connected && vehicle.accepted, "Simulated platform operation");
        }
    }

    @Override
    public void notifyUser(String text) {
        event("scenarioNotice", text, 0);
    }

    private void event(String op, String text, int value) {
        Bundle data = new Bundle();
        data.putString("op", op);
        data.putString("text", text);
        data.putInt("value", value);
        data.putLong("elapsed", android.os.SystemClock.elapsedRealtime());
        trace.accept(data);
    }

    void close() {
        subscription.close();
        lightListeners.clear();
        driveListeners.clear();
        cabinListeners.clear();
        speedListeners.clear();
    }
}
