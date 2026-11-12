package ru.big.town.anative;

import android.content.Context;

import java.util.Collections;

/** The OEM rear-fog command is a pulse; on/off require an observed LightStatus first. */
final class RearFogController {
    private static final OemVehicleStateTransport.StateKey KEY =
            new OemVehicleStateTransport.StateKey("REAR_FOG_LIGHT", 218);

    static String execute(Context context, String action) {
        return execute(context, action, target -> {});
    }

    static String execute(
            Context context, String action, java.util.function.Consumer<String> resolved) {
        String result =
                OemVehicleStateTransport.withSession(
                        context,
                        Collections.singleton(KEY),
                        session -> execute(session, action, resolved));
        return result == null ? "Нет подключения к сервису автомобиля" : result;
    }

    static String execute(OemVehicleStateTransport.Session session, String action) {
        return execute(session, action, target -> {});
    }

    private static String execute(
            OemVehicleStateTransport.Session session,
            String action,
            java.util.function.Consumer<String> resolved) {
        Integer state = session.readRearFog();
        if (state == null || (state != 0 && state != 1)) {
            return "Не удалось прочитать состояние задней противотуманки";
        }
        boolean target;
        switch (action) {
            case "rear_fog:on":
                target = true;
                break;
            case "rear_fog:off":
                target = false;
                break;
            case "rear_fog:toggle":
                target = state == 0;
                break;
            default:
                return "Неизвестное действие";
        }
        resolved.accept("rear_fog:" + (target ? "on" : "off"));
        if (target == (state == 1)) return "";
        return session.sendVehicleState(new OemVehicleStateTransport.StateValue(KEY, 1), "rear fog")
                        .accepted()
                ? ""
                : "Не удалось отправить команду задней противотуманке";
    }

    private RearFogController() {}
}
