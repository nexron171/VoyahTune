package ru.big.town.anative;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class HeadlightCommandController {
    private static final String TAG = "HeadlightCommandController";

    private HeadlightCommandController() {}

    static void select(Context context, String action, Consumer<Boolean> completion) {
        HeadlightCanPolicy.Command command = HeadlightCanPolicy.commandForAction(action);
        if (command == null) {
            completion.accept(false);
            return;
        }

        Context application = context.getApplicationContext();
        AtomicBoolean sent = new AtomicBoolean();
        ManualAutoGate.Ticket manualTicket = LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postUserCommand(
                action,
                () -> sent.set(apply(application, command)),
                () -> {
                    manualTicket.close();
                    completion.accept(sent.get());
                });
    }

    /** The caller must serialize this write and hold a manual-headlight ticket. */
    static boolean apply(Context context, HeadlightCanPolicy.Command command) {
        boolean previousManualAuto =
                LightSensorService.setManualAutoOverride(
                        command == HeadlightCanPolicy.Command.AUTO_LAMP_SWITCH);
        if (!HeadlightCanTransport.send(context, command)) {
            LightSensorService.setManualAutoOverride(previousManualAuto);
            Log.w(TAG, "Failed to send " + command.vehicleStateName);
            return false;
        }

        boolean lowBeam = command == HeadlightCanPolicy.Command.LOW_BEAM;
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("steerHeadlightsOn", lowBeam)
                .putBoolean("steerHeadlightsAutoLowBeam", lowBeam)
                .apply();
        return true;
    }
}
