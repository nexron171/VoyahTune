package ru.big.town.anative;

import android.os.Handler;
import android.widget.Toast;

import ru.big.town.common.ScenarioProtocol;

import java.util.Calendar;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Event, snapshot and action boundaries shared by the scenario engine and emulator. */
interface ScenarioEnvironment {
    interface Subscription {
        void close();
    }

    Subscription gear(Handler handler, IntConsumer listener);

    Subscription doors(Handler handler, IntConsumer listener);

    Subscription driveMode(Handler handler, Consumer<String> listener);

    Subscription light(IntConsumer listener);

    ScenarioPolicy.Snapshot snapshot(ScenarioDefinition scenario);

    void execute(String action, ScenarioActionExecutor.Completion completion);

    void notifyUser(String text);

    default boolean acceptTrigger(
            String scenarioId, ScenarioDefinition.Trigger trigger, ScenarioTriggerHistory history) {
        return history.accept(scenarioId, trigger);
    }

    static ScenarioEnvironment oem(SetModesService service) {
        return new ScenarioEnvironment() {
            private final ScenarioActionExecutor actions = new ScenarioActionExecutor(service);
            // Triggers and conditions consume the same ordered feedback stream.
            private volatile String driveMode;

            @Override
            public Subscription gear(Handler handler, IntConsumer listener) {
                GearStateController.Subscription subscription =
                        VehicleStateControllers.get(service)
                                .gear()
                                .subscribe(handler, listener::accept);
                return subscription::close;
            }

            @Override
            public Subscription doors(Handler handler, IntConsumer listener) {
                DoorsStateController.Subscription subscription =
                        VehicleStateControllers.get(service)
                                .doors()
                                .subscribe(handler, listener::accept);
                return subscription::close;
            }

            @Override
            public Subscription driveMode(Handler handler, Consumer<String> listener) {
                CanBusEventHub.Subscription subscription =
                        CanBusEventHub.get(service)
                                .subscribe(
                                        CanBusEventRouter.INTEREST_CONNECTION
                                                | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                                        new int[] {ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID},
                                        handler,
                                        event -> {
                                            if (event.kind == CanBusEvent.Kind.CONNECTION
                                                    || event.kind
                                                            == CanBusEvent.Kind.CONNECTION_LOST) {
                                                driveMode = null;
                                                listener.accept(null);
                                            } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE
                                                    && event.first
                                                            == ModeFeedbackDecoder
                                                                    .DRIVE_MODE_VSTATE_ID) {
                                                ModeFeedbackDecoder.Feedback feedback =
                                                        ModeFeedbackDecoder.decode(
                                                                event.first, event.second);
                                                driveMode = feedback == null ? null : feedback.mode;
                                                listener.accept(driveMode);
                                            }
                                        });
                return subscription::close;
            }

            @Override
            public Subscription light(IntConsumer listener) {
                LightSensorService.LevelListener callback = listener::accept;
                LightSensorService.addLevelListener(callback);
                return () -> LightSensorService.removeLevelListener(callback);
            }

            @Override
            public ScenarioPolicy.Snapshot snapshot(ScenarioDefinition scenario) {
                VehicleStateControllers states = VehicleStateControllers.get(service);
                String drive = driveMode;
                Integer soc = null;
                if (ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_SOC)) {
                    OemVehicleStateTransport.StateKey key =
                            new OemVehicleStateTransport.StateKey(
                                    PowerHoldPolicy.BMS_SOC_DISPLAY,
                                    PowerHoldPolicy.BMS_SOC_DISPLAY_ID);
                    try {
                        Map<OemVehicleStateTransport.StateKey, Integer> values =
                                OemVehicleStateTransport.readVehicleStates(
                                        service, Collections.singletonList(key));
                        if (values != null) soc = values.get(key);
                    } catch (RuntimeException ignored) {
                    }
                }
                int temperature =
                        ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_TEMP_OUT)
                                ? states.latestAmbientTemperature()
                                : VehicleStateControllers.NO_AMBIENT_TEMPERATURE;
                Calendar calendar = Calendar.getInstance();
                return new ScenarioPolicy.Snapshot(
                        ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_LIGHT)
                                ? LightSensorService.lastKnownLevel()
                                : -1,
                        temperature == VehicleStateControllers.NO_AMBIENT_TEMPERATURE
                                ? ScenarioPolicy.Snapshot.UNKNOWN
                                : temperature,
                        soc == null ? -1 : soc,
                        drive,
                        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
                        ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1,
                        states.gear().currentGear(),
                        ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_TEMP_IN)
                                ? CabinTemperatureController.get(service).temperature()
                                : Double.NaN);
            }

            @Override
            public boolean acceptTrigger(
                    String scenarioId, ScenarioDefinition.Trigger trigger,
                    ScenarioTriggerHistory history) {
                return !trigger.firstOnly
                        || ScenarioTriggerPersistence.accept(service, scenarioId, trigger);
            }

            @Override
            public void execute(String action, ScenarioActionExecutor.Completion completion) {
                actions.execute(action, completion);
            }

            @Override
            public void notifyUser(String text) {
                Toast.makeText(service, text, Toast.LENGTH_LONG).show();
            }
        };
    }
}
