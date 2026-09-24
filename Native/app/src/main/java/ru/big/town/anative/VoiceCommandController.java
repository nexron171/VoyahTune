package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Invoked only through SetModesService's signature-protected binding, never public broadcasts. */
final class VoiceCommandController {
    private final SetModesService service;
    private final VoiceSessionGate gate = new VoiceSessionGate();
    private final Handler main = new Handler(Looper.getMainLooper());

    VoiceCommandController(SetModesService service) { this.service = service; }

    void close() { gate.clear(); main.removeCallbacksAndMessages(null); }

    void handle(Bundle data) {
        if (data == null) return;
        String token = data.getString("session", "");
        if (token.isEmpty()) return;
        String operation = data.getString("op", "");
        if ("begin".equals(operation)) { gate.begin(token); return; }
        if ("cancel".equals(operation)) { gate.cancel(token); return; }
        if (!"execute".equals(operation) || !gate.submit(token)) return;
        String action = data.getString("action", "");
        ResultReceiver reply = data.getParcelable("reply");
        long deadline = SystemClock.elapsedRealtime() + 7000;
        // Let the translucent activity finish before navigating or issuing GLOBAL_ACTION_BACK.
        if (isNavigation(action)) {
            int resultDisplayMs = Math.max(0, Math.min(3000, data.getInt("resultDisplayMs", 0)));
            if (action.startsWith("app:") && service.getPackageManager()
                    .getLaunchIntentForPackage(action.substring(4)) == null) {
                respond(reply, false, "Не удалось открыть приложение");
                return;
            }
            // Success means the service accepted the request, as for other voice commands.
            // Older clients omit this field and retain the original immediate navigation flow.
            if (resultDisplayMs > 0) respond(reply, true, null);
            main.postDelayed(() -> {
                if (!gate.active(token) || SystemClock.elapsedRealtime() > deadline) return;
                try {
                    if ("system_back".equals(action)) BackButtonService.performBack(service);
                    else if (action.startsWith("app:")) {
                        String pkg = action.substring(4);
                        if (BuildConfig.IS_FULL) {
                            ClusterMediaHostActivity.closeForPackage(pkg);
                            SplitHostActivity.closeActiveHost();
                            boolean fullscreen = FullscreenPackagePolicy.contains(
                                    android.provider.Settings.Global.getString(service.getContentResolver(), "voyahtune_fullscreen_apps"), pkg);
                            AppDisplayLauncher.launch(service, pkg, 0, fullscreen,
                                    () -> gate.active(token), () -> respond(reply, false, "Не удалось открыть приложение"));
                        } else {
                            Intent launch = service.getPackageManager().getLaunchIntentForPackage(pkg);
                            if (launch == null) throw new IllegalArgumentException("No launcher");
                            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    } else SetModesReceiverDynamic.handleSteerAction(service, action, null);
                    if (resultDisplayMs == 0) respond(reply, true, null);
                } catch (RuntimeException e) { respond(reply, false, "Не удалось открыть приложение"); }
            }, resultDisplayMs + 350L);
            return;
        }
        if (service.isVoiceServiceAction(action)) {
            if (gate.active(token)) service.executeVoiceServiceAction(action, reply);
            return;
        }
        if (action.equals("power_hold")) {
            service.activateVoicePowerHold(() -> gate.active(token) && SystemClock.elapsedRealtime() <= deadline,
                    result -> {
                        if (!gate.active(token)) return;
                        String error = result == PowerHoldPolicy.Outcome.NOT_IN_PARK ? "Для Power Hold переведите селектор в P"
                                : result == PowerHoldPolicy.Outcome.LOW_BATTERY ? "Для Power Hold нужен заряд не ниже 15%"
                                : "Автомобиль не принял команду Power Hold";
                        respond(reply, result == PowerHoldPolicy.Outcome.ACCEPTED, error);
                    });
            return;
        }
        AtomicBoolean accepted = new AtomicBoolean();
        Bundle details = new Bundle();
        String[] error = {"Автомобиль не принял команду"};
        boolean headlights = action.startsWith("headlights:") || action.startsWith("toggle_headlights");
        ManualAutoGate.Ticket ticket = headlights ? LightSensorService.reserveManualHeadlightCommand() : null;
        ApplyEngine.postUserCommand("voice " + action, () -> {
            if (!gate.active(token) || SystemClock.elapsedRealtime() > deadline) return;
            try {
                // The charge target and SREV selection are two writes: recheck before each one.
                CanSender.runGuardedAction(
                        () -> gate.active(token) && SystemClock.elapsedRealtime() <= deadline,
                        () -> accepted.set(execute(action, error, details)));
            }
            catch (RuntimeException e) { android.util.Log.e("VoyahVoice", "Command failed: " + action, e); }
        }, () -> {
            if (ticket != null) ticket.close();
            if (gate.active(token)) respond(reply, accepted.get(), error[0], details);
        });
    }

    private static boolean isNavigation(String action) {
        return "system_back".equals(action) || "open_voyahtune".equals(action)
                || action.startsWith("app:") || action.startsWith("split:") || action.startsWith("call:");
    }

    private boolean execute(String action, String[] error, Bundle details) {
        if (action.startsWith("port_cap:")) {
            PortCapController.OpenResult opened = PortCapController.open(service, action);
            PortCapController.Outcome result = opened.outcome;
            if (result == PortCapController.Outcome.ACCEPTED && opened.refillLiters != null) {
                details.putInt("fuelRefillLiters", opened.refillLiters);
            }
            if (result == PortCapController.Outcome.NOT_IN_PARK) {
                error[0] = "Для открытия лючка переведите селектор в P";
            } else if (result == PortCapController.Outcome.STATE_UNAVAILABLE) {
                error[0] = "Не удалось проверить положение селектора";
            } else if (result == PortCapController.Outcome.TRANSPORT_FAILURE) {
                error[0] = "Не удалось отправить команду открытия лючка";
            }
            return result == PortCapController.Outcome.ACCEPTED;
        }
        if (action.startsWith("fuel_charge:")) {
            final int percent;
            try {
                percent = Integer.parseInt(action.substring("fuel_charge:".length()));
                VehicleRestorePolicy.requireSaveChargeLevel(percent);
            } catch (IllegalArgumentException e) {
                error[0] = "Некорректный уровень поддержания заряда";
                return false;
            }
            boolean sent = MainActivity.sendSaveChargeCommand(service, percent);
            if (sent) {
                // An explicit SREV selection supersedes forced EV, including the restore snapshot.
                MainActivity.persistSavedToggle(service, "forcedEv", false);
                ApplyEngine.noteVehicleMode("energy", "SREV");
                MainActivity.persistSavedMode(service, "energy", "SREV");
            }
            return sent;
        }
        if (action.startsWith("drive:") || action.startsWith("energy:") || action.startsWith("recycle:")) {
            int split = action.indexOf(':');
            String type = action.substring(0, split);
            String key = "drive".equals(type) ? "driveMode" : type;
            String mode = SteeringActionPolicy.nextMode(action.substring(split + 1), MainActivity.currentVehicleMode(service, key));
            if (mode == null) return false;
            boolean sent = "drive".equals(type) ? MainActivity.sendDriveModeCommand(service, mode)
                    : "energy".equals(type) ? MainActivity.sendEnergyModeCommand(service, mode)
                    : MainActivity.sendRecuperationModeCommand(service, mode);
            if (sent) { ApplyEngine.noteVehicleMode(key, mode); MainActivity.persistSavedMode(service, key, mode); }
            return sent;
        }
        if (action.startsWith("forced_ev:") || "toggle_forced_ev".equals(action)) {
            boolean on = "toggle_forced_ev".equals(action) ? !MainActivity.currentSavedToggle(service, "forcedEv") : action.endsWith(":on");
            boolean sent = MainActivity.sendForcedEvCommand(on);
            if (sent) MainActivity.persistSavedToggle(service, "forcedEv", on);
            return sent;
        }
        if (action.startsWith("pedestrian:") || "toggle_pedestrian_sound".equals(action)) {
            boolean disabled = "toggle_pedestrian_sound".equals(action)
                    ? !MainActivity.currentSavedToggle(service, "disablePedestrianSound") : action.endsWith(":off");
            boolean sent = MainActivity.sendPedestrianSoundCommand(disabled);
            if (sent) MainActivity.persistSavedToggle(service, "disablePedestrianSound", disabled);
            return sent;
        }
        if (action.startsWith("headlights:") || action.startsWith("toggle_headlights")) {
            android.content.SharedPreferences prefs = service.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
            boolean autoPair = action.equals("headlights:auto") || action.equals("toggle_headlights_auto");
            boolean on = action.equals("toggle_headlights") ? !prefs.getBoolean("steerHeadlightsOn", false)
                    : action.equals("toggle_headlights_auto") ? !prefs.getBoolean("steerHeadlightsAutoLowBeam", false)
                    : action.equals("headlights:on");
            boolean previous = LightSensorService.setManualAutoOverride(autoPair && !on);
            boolean sent = autoPair ? MainActivity.setHeadlightsAutoLow(service, on) : MainActivity.setHeadlights(service, on);
            if (!sent) LightSensorService.setManualAutoOverride(previous);
            else prefs.edit().putBoolean("steerHeadlightsOn", on).putBoolean("steerHeadlightsAutoLowBeam", on).apply();
            return sent;
        }
        if (action.equals("wash")) {
            WashModePolicy.Outcome result = service.activateVoiceWash();
            if (result == WashModePolicy.Outcome.NOT_IN_PARK) error[0] = "Для режима мойки переведите селектор в P";
            return result == WashModePolicy.Outcome.ACCEPTED;
        }
        if (action.startsWith("can:")) {
            byte[] frame = SteeringActionSequence.parseCustomCan(action);
            return frame != null && MainActivity.setCanValues(1, new byte[][]{frame}, "voice saved CAN");
        }
        error[0] = "Неизвестное действие";
        return false;
    }

    static void respond(ResultReceiver reply, boolean accepted, String error) {
        respond(reply, accepted, error, new Bundle());
    }

    private static void respond(ResultReceiver reply, boolean accepted, String error, Bundle result) {
        if (reply == null) return;
        result.putString("error", error);
        reply.send(accepted ? 1 : 0, result);
    }
}
