package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Composition root for shared vehicle state.
 *
 * <p>This routes drive mode, gear, driver-door, all-doors and ambient-temperature events from
 * {@link CanBusEventHub} to typed controllers. Trip recording also consumes gear with connection
 * and energy telemetry in one ordered hub mailbox. Other domain consumers use the typed
 * controllers.</p>
 */
final class VehicleStateControllers {
    private static final String TAG = "VehicleStateControllers";
    private static volatile VehicleStateControllers instance;

    static VehicleStateControllers get(Context context) {
        VehicleStateControllers current = instance;
        if (current != null) return current;
        synchronized (VehicleStateControllers.class) {
            current = instance;
            if (current == null) {
                current = new VehicleStateControllers(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    /** Нет данных уличной температуры (так отдаёт CanBusService). */
    static final int NO_AMBIENT_TEMPERATURE = Integer.MIN_VALUE;

    private final Context appContext;
    private final ModeRestoreTriggers restoreTriggers = new ModeRestoreTriggers();
    private final boolean accHooks;
    private final CanBusEventHub canBusEventHub;
    private final HandlerThread stateThread;
    private final Handler stateHandler;
    private final GearStateController gearStateController;
    private final DriverDoorStateController driverDoorStateController;
    private final DoorsStateController doorsStateController;
    private final ModeFeedbackController modeFeedbackController;
    /** Последняя уличная температура (°C) или {@link #NO_AMBIENT_TEMPERATURE}. */
    private volatile int latestAmbientTemperature = NO_AMBIENT_TEMPERATURE;
    /** Последнее значение stable-id 545 (DRIVING_MODE_SET) или -1, если ещё не приходило. */
    private volatile int latestDriveModeValue = -1;
    @SuppressWarnings("FieldCanBeLocal")
    private final CanBusEventHub.Subscription canBusSubscription;

    private VehicleStateControllers(Context context) {
        appContext = context;
        accHooks = ru.big.town.common.InfrastructureProfile.read(context).usesAccHooks();
        stateThread = new HandlerThread("VehicleStateControllers");
        stateThread.start();
        stateHandler = new Handler(stateThread.getLooper());
        gearStateController = new GearStateController(stateHandler);
        driverDoorStateController = new DriverDoorStateController(stateHandler);
        doorsStateController = new DoorsStateController(stateHandler);

        ModeFeedbackController feedback = null;
        try {
            feedback = ModeFeedbackController.create(appContext, stateHandler);
        } catch (RuntimeException e) {
            Log.w(TAG, "start mode feedback: " + e.getMessage());
        }
        modeFeedbackController = feedback;

        canBusEventHub = CanBusEventHub.get(appContext);
        try {
            canBusSubscription = canBusEventHub.subscribe(
                    CanBusEventRouter.INTEREST_CONNECTION
                            | CanBusEventRouter.INTEREST_DOOR
                            | CanBusEventRouter.INTEREST_GEAR
                            | CanBusEventRouter.INTEREST_VEHICLE_STATE
                            | CanBusEventRouter.INTEREST_AMBIENT_TEMPERATURE,
                    new int[]{
                            ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID,
                            ModeFeedbackDecoder.ENERGY_MODE_VSTATE_ID,
                            ModeFeedbackDecoder.RECYCLE_MODE_VSTATE_ID
                    },
                    stateHandler, this::onCanBusEvent);
        } catch (RuntimeException e) {
            if (modeFeedbackController != null) modeFeedbackController.close();
            stateThread.quitSafely();
            throw e;
        }
    }

    GearStateController gear() {
        return gearStateController;
    }

    DriverDoorStateController driverDoor() {
        return driverDoorStateController;
    }

    /** Состояние всех дверей для сценариев; водительская дверь остаётся в {@link #driverDoor()}. */
    DoorsStateController doors() {
        return doorsStateController;
    }

    /** Последняя уличная температура (°C) или {@link #NO_AMBIENT_TEMPERATURE}. */
    int latestAmbientTemperature() {
        return latestAmbientTemperature;
    }

    /** Последний stable-id 545 (DRIVING_MODE_SET) или -1, если данных ещё нет. */
    int latestDriveModeValue() {
        return latestDriveModeValue;
    }

    private void onCanBusEvent(CanBusEvent event) {
        switch (event.kind) {
            case CONNECTION:
                // Connection replay only refreshes observable state.
                gearStateController.reset();
                driverDoorStateController.reset();
                doorsStateController.reset();
                latestAmbientTemperature = NO_AMBIENT_TEMPERATURE;
                latestDriveModeValue = -1;
                canBusEventHub.requestDriverDoorSeed();
                if (modeFeedbackController != null) modeFeedbackController.onConnected();
                break;
            case CONNECTION_LOST:
                gearStateController.reset();
                driverDoorStateController.reset();
                doorsStateController.reset();
                latestAmbientTemperature = NO_AMBIENT_TEMPERATURE;
                latestDriveModeValue = -1;
                break;
            case DOOR:
                if (!accHooks) {
                    if (event.first == 1) ApplyEngine.stopEarlyDriveRestore("driver door open");
                    if (restoreTriggers.onDoor(event.first)) {
                        ApplyEngine.noteDriverDoorOpened();
                        ApplyEngine.scheduleNativeApply("driver door opened");
                    }
                }
                driverDoorStateController.accept(
                        event.first,
                        event.origin == CanBusEvent.Origin.LIVE
                                ? DriverDoorStateController.Source.LIVE
                                : DriverDoorStateController.Source.SNAPSHOT);
                doorsStateController.accept(event.second);
                break;
            case GEAR:
                if (!accHooks && event.origin == CanBusEvent.Origin.LIVE) {
                    if (event.first == 3) ApplyEngine.stopEarlyDriveRestore("gear Drive");
                    if (restoreTriggers.onGear(event.first)) ApplyEngine.scheduleNativeApply("gear Drive");
                    ApplyEngine.noteGear(event.first);
                }
                gearStateController.accept(event.first);
                break;
            case VEHICLE_STATE:
                if (event.first == ModeFeedbackDecoder.DRIVE_MODE_VSTATE_ID) {
                    latestDriveModeValue = event.second;
                }
                if (modeFeedbackController != null) {
                    modeFeedbackController.onVehicleState(event.first, event.second);
                }
                break;
            case AMBIENT_TEMPERATURE:
                latestAmbientTemperature = event.first;
                break;
            default:
                break;
        }
    }
}
