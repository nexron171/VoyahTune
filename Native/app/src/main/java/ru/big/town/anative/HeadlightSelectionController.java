package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ru.big.town.common.HeadlightWidgetProtocol;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Durable widget intent; wake retries cannot overwrite a newer explicit selection. */
final class HeadlightSelectionController {
    static final String SAVED_MODE = "headlightWidgetMode";
    private static final AtomicLong REVISION = new AtomicLong();

    private HeadlightSelectionController() {}

    static int selected(Context context) {
        return preferences(context).getInt(SAVED_MODE, HeadlightWidgetProtocol.UNKNOWN);
    }

    static boolean suspendsAutomatic(int mode) {
        return HeadlightWidgetProtocol.validMode(mode) && mode != HeadlightWidgetProtocol.AUTO;
    }

    static boolean automaticActive(Context context) {
        return selected(context) == HeadlightWidgetProtocol.AUTO
                && preferences(context).getBoolean("autoLight", false);
    }

    static void select(Context context, int mode, Consumer<Boolean> completion) {
        String action = HeadlightWidgetProtocol.action(mode);
        if (action == null) {
            completion.accept(false);
            return;
        }
        Context application = context.getApplicationContext();
        REVISION.incrementAndGet();
        AtomicBoolean sent = new AtomicBoolean();
        ManualAutoGate.Ticket ticket = LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postUserCommand(
                "headlight widget " + action,
                () -> {
                    if (HeadlightCommandController.apply(
                            application, HeadlightCanPolicy.commandForAction(action))) {
                        preferences(application).edit().putInt(SAVED_MODE, mode).apply();
                        sent.set(true);
                        AutoLightSettings.refreshObservation(application);
                    }
                },
                () -> {
                    ticket.close();
                    try {
                        if (sent.get() && automaticActive(application)) {
                            LightSensorService.resumeAutomaticControl(application);
                        }
                    } finally {
                        completion.accept(sent.get());
                    }
                });
    }

    /** Called after restoring the auto-light setting, on startup and each physical wake. */
    static void restore(Context context) {
        int mode = selected(context);
        if (!HeadlightWidgetProtocol.validMode(mode)) {
            return;
        }
        if (automaticActive(context)) {
            LightSensorService.resumeAutomaticControl(context);
            return;
        }
        HeadlightCanTransport.initialize(context);
        restore(
                context.getApplicationContext(),
                mode,
                REVISION.get(),
                ApplyEngine.capturePhysicalWakeGeneration(),
                0);
    }

    private static void restore(Context context, int mode, long revision, long wake, int attempt) {
        if (!current(context, mode, revision, wake)) {
            return;
        }
        ManualAutoGate.Ticket ticket = LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postWakeAction(
                "restore headlight widget selection",
                () ->
                        current(context, mode, revision, wake)
                                && HeadlightCommandController.apply(
                                        context,
                                        HeadlightCanPolicy.commandForAction(
                                                HeadlightWidgetProtocol.action(mode))),
                result -> {
                    ticket.close();
                    if (result == ApplyEngine.WakeActionResult.FAILED
                            && current(context, mode, revision, wake)) {
                        if (attempt < 9) {
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(
                                            () ->
                                                    restore(
                                                            context,
                                                            mode,
                                                            revision,
                                                            wake,
                                                            attempt + 1),
                                            1000);
                        } else {
                            Log.w(
                                    "HeadlightSelection",
                                    "Saved headlight command unavailable after wake");
                        }
                    }
                });
    }

    private static boolean current(Context context, int mode, long revision, long wake) {
        return restoreIsCurrent(
                mode,
                selected(context),
                revision,
                REVISION.get(),
                ApplyEngine.isPhysicalWakeGenerationActive(wake),
                automaticActive(context));
    }

    static boolean restoreIsCurrent(
            int requested,
            int saved,
            long revision,
            long currentRevision,
            boolean awake,
            boolean automatic) {
        return HeadlightWidgetProtocol.validMode(requested)
                && requested == saved
                && revision == currentRevision
                && awake
                && !automatic;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }
}
