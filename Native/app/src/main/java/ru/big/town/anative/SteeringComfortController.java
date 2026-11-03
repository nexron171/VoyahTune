package ru.big.town.anative;

import android.content.Context;

import java.util.Collections;
import java.util.function.Consumer;

/** Switch writes retain the OEM seat intensity and massage program. */
final class SteeringComfortController {
    private static final OemVehicleStateTransport.StateKey DRIVER_MASSAGE =
            new OemVehicleStateTransport.StateKey("FRONT_SEAT_MASS_SWITCH_LEFT", 702);

    private SteeringComfortController() {}

    static boolean handles(String action) {
        return keyForAction(action) != null;
    }

    static void execute(Context context, String action, Consumer<String> completion) {
        OemVehicleStateTransport.StateKey key = keyForAction(action);
        if (key == null) {
            completion.accept("Неизвестное действие комфорта");
            return;
        }
        String[] error = {"Не удалось отправить команду автомобилю"};
        ApplyEngine.postIndependentUserCommand(
                "steering comfort " + action,
                () -> {
                    String result =
                            OemVehicleStateTransport.withSession(
                                    context,
                                    Collections.singleton(key),
                                    session -> execute(session, action));
                    error[0] = result == null ? "Нет подключения к сервису автомобиля" : result;
                },
                () -> completion.accept(error[0]));
    }

    static String execute(OemVehicleStateTransport.Session session, String action) {
        OemVehicleStateTransport.StateKey key = keyForAction(action);
        if (key == null) {
            return "Неизвестное действие комфорта";
        }
        int target;
        if (action.endsWith(":toggle")) {
            Integer current = session.readVehicleState(key);
            if (current == null || (current != 1 && current != 2)) {
                return "Не удалось прочитать состояние обогрева, вентиляции или массажа";
            }
            target = current == 1 ? 2 : 1;
        } else {
            target = action.endsWith(":on") ? 2 : 1;
        }
        return session.sendVehicleState(
                                new OemVehicleStateTransport.StateValue(key, target),
                                "steering comfort " + action)
                        .accepted()
                ? ""
                : "Не удалось отправить команду автомобилю";
    }

    private static OemVehicleStateTransport.StateKey keyForAction(String action) {
        if (action == null) {
            return null;
        }
        int separator = action.lastIndexOf(':');
        if (separator < 0) {
            return null;
        }
        String operation = action.substring(separator + 1);
        if (!operation.equals("on") && !operation.equals("off") && !operation.equals("toggle")) {
            return null;
        }
        switch (action.substring(0, separator)) {
            case "wheel_heat":
                return OemComfortClimateState.STEERING_WHEEL_HEATING;
            case "seat:driver:heat":
                return OemSeatClimateState.Channel.DRIVER_HEATING.power;
            case "seat:driver:vent":
                return OemSeatClimateState.Channel.DRIVER_VENTILATION.power;
            case "seat:driver:massage":
                return DRIVER_MASSAGE;
            default:
                return null;
        }
    }
}
