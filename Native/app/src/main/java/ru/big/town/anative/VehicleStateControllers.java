package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Composition root for shared vehicle state.
 *
 * <p>This is the only subscription to {@link CanBusEventHub} for drive mode, gear and driver-door
 * events. Domain consumers subscribe to the typed controllers instead of depending on CAN event
 * kinds, IDs or snapshot mechanics.</p>
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

    private final ModeRestoreTriggers restoreTriggers = new ModeRestoreTriggers();
    private final Context appContext;
    private final CanBusEventHub canBusEventHub;
    private final HandlerThread stateThread;
    private final Handler stateHandler;
    private final GearStateController gearStateController;
    private final DriverDoorStateController driverDoorStateController;
    private final ModeFeedbackController modeFeedbackController;
    @SuppressWarnings("FieldCanBeLocal")
    private final CanBusEventHub.Subscription canBusSubscription;

    private VehicleStateControllers(Context context) {
        appContext = context;
        stateThread = new HandlerThread("VehicleStateControllers");
        stateThread.start();
        stateHandler = new Handler(stateThread.getLooper());
        gearStateController = new GearStateController(stateHandler);
        driverDoorStateController = new DriverDoorStateController(stateHandler);

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
                            | CanBusEventRouter.INTEREST_VEHICLE_STATE,
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

    private void onCanBusEvent(CanBusEvent event) {
        switch (event.kind) {
            case CONNECTION:
                // Preserve restore history across CAN reconnects: neither replayed states nor
                // reconnecting during parking may grant another Drive restore.
                gearStateController.reset();
                driverDoorStateController.reset();
                canBusEventHub.requestDriverDoorSeed();
                if (modeFeedbackController != null) modeFeedbackController.onConnected();
                break;
            case CONNECTION_LOST:
                gearStateController.reset();
                driverDoorStateController.reset();
                break;
            case DOOR:
                if (restoreTriggers.onDoor(event.first)) {
                    ApplyEngine.noteDriverDoorOpened();
                    ApplyEngine.scheduleApply("driver door opened");
                }
                driverDoorStateController.accept(
                        event.first,
                        event.origin == CanBusEvent.Origin.LIVE
                                ? DriverDoorStateController.Source.LIVE
                                : DriverDoorStateController.Source.SNAPSHOT);
                break;
            case GEAR:
                if (restoreTriggers.onGear(event.first)) {
                    ApplyEngine.scheduleApply("gear Drive");
                }
                // Close the restore gate above before allowing this trip's mode persistence.
                ApplyEngine.noteGear(event.first);
                gearStateController.accept(event.first);
                break;
            case VEHICLE_STATE:
                if (modeFeedbackController != null) {
                    modeFeedbackController.onVehicleState(event.first, event.second);
                }
                break;
            default:
                break;
        }
    }
}
