package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Выполнение действий сценария.
 *
 * <p>Где семантика совпадает с кнопками руля, действие делегируется существующему диспетчеру
 * {@link SetModesReceiverDynamic#handleSteerAction} (режимы, toggle-команды, своя CAN, сплиты,
 * звонки, навигация). Остальное — отдельные on/off, свет, мойка, сиденья/окна, лючки, заряд,
 * Power Hold и сервисные действия — выполняется теми же контроллерами, что и голосовой путь.</p>
 *
 * <p>Работающий голосовой помощник намеренно не изменён: его сессионная обвязка (токен, дедлайн,
 * ответ) остаётся в {@code VoiceCommandController}.</p>
 */
final class ScenarioActionExecutor {
    private static final long DEADLINE_MS = 7000L;

    /** Результат попытки; для последовательностей вызывается ровно один раз. */
    interface Completion {
        void onFinished(boolean accepted, String error);
    }

    private final SetModesService service;

    ScenarioActionExecutor(SetModesService service) {
        this.service = service;
    }

    void execute(String action, Completion completion) {
        if (action == null || action.isEmpty()) {
            completion.onFinished(false, "Пустое действие");
            return;
        }
        // Разовые OEM-записи сидений и окон: после отправки их нельзя откатить или отменить.
        if (SeatCommand.handles(action) || WindowCommand.handles(action)) {
            executeIndependentWrite(action, completion);
            return;
        }
        if (action.startsWith("app:")) {
            launchApp(action.substring(4), completion);
            return;
        }
        if (action.startsWith("port_cap:")) {
            openPortCap(action, completion);
            return;
        }
        if (action.startsWith("fuel_charge:")) {
            applyFuelCharge(action, completion);
            return;
        }
        if ("power_hold".equals(action)) {
            service.activateVoicePowerHold(() -> true, result -> completion.onFinished(
                    result == PowerHoldPolicy.Outcome.ACCEPTED, describePowerHold(result)));
            return;
        }
        if (service.isVoiceServiceAction(action)) {
            runServiceAction(action, completion);
            return;
        }
        if (action.startsWith("headlights:")) {
            setHeadlights(action, completion);
            return;
        }
        if (action.startsWith("suspension_maintenance:")
                || action.startsWith("forced_ev:")
                || action.startsWith("pedestrian:")) {
            setToggle(action, completion);
            return;
        }
        if ("wash".equals(action)) {
            WashModePolicy.Outcome result = service.activateVoiceWash();
            completion.onFinished(result == WashModePolicy.Outcome.ACCEPTED,
                    result == WashModePolicy.Outcome.NOT_IN_PARK
                            ? "Для режима мойки переведите селектор в P" : null);
            return;
        }
        // Режимы, toggle-команды, своя CAN и системная навигация: семантика совпадает с рулём.
        SetModesReceiverDynamic.handleSteerAction(service, action,
                () -> completion.onFinished(true, null));
    }

    private void executeIndependentWrite(String action, Completion completion) {
        String[] error = {null};
        ApplyEngine.postIndependentUserCommand("scenario " + action,
                () -> error[0] = OemCommandSender.send(action,
                        SystemClock.elapsedRealtime() + DEADLINE_MS, service.voiceTransport()),
                () -> completion.onFinished(error[0] == null,
                        error[0] == null ? null : "Не удалось отправить команду автомобилю"));
    }

    /** Открытие приложения через сплит-хост на физическом экране (как в Full-ветке голоса). */
    private void launchApp(String pkg, Completion completion) {
        // Неудача запуска может прийти позже, когда об успехе уже сообщено: результат ровно один,
        // иначе последовательность перескочит следующий шаг.
        final AtomicBoolean settled = new AtomicBoolean();
        try {
            ClusterMediaHostActivity.closeForPackage(pkg);
            SplitHostActivity.closeActiveHost();
            boolean fullscreen = FullscreenPackagePolicy.contains(
                    android.provider.Settings.Global.getString(
                            service.getContentResolver(), "voyahtune_fullscreen_apps"), pkg);
            AppDisplayLauncher.launch(service, pkg, 0, fullscreen, () -> true,
                    () -> settleFailure(settled, completion));
            if (settled.compareAndSet(false, true)) completion.onFinished(true, null);
        } catch (RuntimeException e) {
            settleFailure(settled, completion);
        }
    }

    private static void settleFailure(AtomicBoolean settled, Completion completion) {
        if (settled.compareAndSet(false, true)) {
            completion.onFinished(false, "Не удалось открыть приложение");
        }
    }

    private void openPortCap(String action, Completion completion) {
        PortCapController.OpenResult opened = PortCapController.open(service, action);
        String error = opened.outcome == PortCapController.Outcome.NOT_IN_PARK
                ? "Для открытия лючка переведите селектор в P"
                : opened.outcome == PortCapController.Outcome.STATE_UNAVAILABLE
                ? "Не удалось проверить положение селектора"
                : opened.outcome == PortCapController.Outcome.TRANSPORT_FAILURE
                ? "Не удалось отправить команду открытия лючка" : null;
        completion.onFinished(opened.outcome == PortCapController.Outcome.ACCEPTED, error);
    }

    private void applyFuelCharge(String action, Completion completion) {
        final int percent;
        try {
            percent = Integer.parseInt(action.substring("fuel_charge:".length()));
            VehicleRestorePolicy.requireSaveChargeLevel(percent);
        } catch (IllegalArgumentException e) {
            completion.onFinished(false, "Некорректный уровень поддержания заряда");
            return;
        }
        SaveChargeSequence.Result result = SaveChargeController.apply(service, percent, () -> true);
        boolean confirmed = result.outcome == SaveChargeSequence.Outcome.CONFIRMED;
        completion.onFinished(confirmed,
                confirmed ? null : SaveChargeController.error(result, percent));
    }

    private void runServiceAction(String action, Completion completion) {
        try {
            service.executeVoiceServiceAction(action, replyReceiver(completion));
        } catch (RuntimeException e) {
            completion.onFinished(false, "Не удалось выполнить команду");
        }
    }

    private void setHeadlights(String action, Completion completion) {
        final boolean autoPair = "headlights:auto".equals(action);
        final boolean on = !"headlights:off".equals(action);
        final AtomicBoolean accepted = new AtomicBoolean();
        final ManualAutoGate.Ticket ticket = LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postUserCommand("scenario " + action, () -> {
            boolean previous = LightSensorService.setManualAutoOverride(autoPair && !on);
            boolean sent = autoPair ? MainActivity.setHeadlightsAutoLow(service, on)
                    : MainActivity.setHeadlights(service, on);
            if (!sent) {
                LightSensorService.setManualAutoOverride(previous);
            } else {
                accepted.set(true);
                service.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("steerHeadlightsOn", on)
                        .putBoolean("steerHeadlightsAutoLowBeam", on).apply();
            }
        }, () -> {
            ticket.close();
            completion.onFinished(accepted.get(),
                    accepted.get() ? null : "Автомобиль не принял команду");
        });
    }

    private void setToggle(String action, Completion completion) {
        final String key;
        final boolean value;
        if (action.startsWith("suspension_maintenance:")) {
            key = "suspensionMaintenance";
            value = action.endsWith(":on");
        } else if (action.startsWith("forced_ev:")) {
            key = "forcedEv";
            value = action.endsWith(":on");
        } else {
            // В prefs инвертированная семантика: true = звук пешеходов выключен.
            key = "disablePedestrianSound";
            value = action.endsWith(":off");
        }
        final AtomicBoolean accepted = new AtomicBoolean();
        ApplyEngine.postUserCommand("scenario " + action, () -> {
            boolean sent = "forcedEv".equals(key) ? MainActivity.sendForcedEvCommand(value)
                    : "suspensionMaintenance".equals(key)
                    ? MainActivity.sendSuspensionMaintenanceCommand(service, value)
                    : MainActivity.sendPedestrianSoundCommand(value);
            if (sent) {
                MainActivity.persistSavedToggle(service, key, value);
                accepted.set(true);
            }
        }, () -> completion.onFinished(accepted.get(),
                accepted.get() ? null : "Автомобиль не принял команду"));
    }

    private static String describePowerHold(PowerHoldPolicy.Outcome result) {
        if (result == PowerHoldPolicy.Outcome.ACCEPTED) return null;
        if (result == PowerHoldPolicy.Outcome.NOT_IN_PARK) return "Для Power Hold переведите селектор в P";
        if (result == PowerHoldPolicy.Outcome.LOW_BATTERY) return "Для Power Hold нужен заряд не ниже 15%";
        return "Автомобиль не принял команду Power Hold";
    }

    private static ResultReceiver replyReceiver(Completion completion) {
        return new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                completion.onFinished(resultCode == 1,
                        resultData == null ? null : resultData.getString("error"));
            }
        };
    }
}
