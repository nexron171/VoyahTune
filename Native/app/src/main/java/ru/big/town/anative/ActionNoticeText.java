package ru.big.town.anative;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ActionNoticeText {
    static String action(String action, Function<String, String> applicationName) {
        String[] parts = action.split(":");
        String value = parts[parts.length - 1];
        String operation = "on".equals(value) ? "включение" : "выключение";
        if (action.startsWith("wheel_heat:")) return "Обогрев руля: " + operation;
        if (action.startsWith("seat:driver:heat:")) return "Обогрев сиденья водителя: " + operation;
        if (action.startsWith("seat:driver:vent:"))
            return "Вентиляция сиденья водителя: " + operation;
        if (action.startsWith("seat:driver:massage:")) return "Массаж водителя: " + operation;
        if (action.startsWith("energy:")) return "Энергорежим: " + mode(value);
        if (action.startsWith("drive:")) return "Режим движения: " + mode(value);
        if (action.startsWith("recycle:")) return "Рекуперация: " + mode(value);
        if (action.startsWith("suspension_maintenance:"))
            return "Сервисный режим подвески: " + operation;
        if (action.startsWith("forced_ev:")) return "Force EV: " + operation;
        if (action.startsWith("pedestrian:")) return "Звук предупреждения пешеходов: " + operation;
        if (action.startsWith("rear_fog:")) return "Задний противотуманный фонарь: " + operation;
        if (action.startsWith("hotspot:")) return "Точка доступа: " + operation;
        if (action.startsWith("headlights:")) return "Фары: " + mode(value);
        if (action.startsWith("app:"))
            return "Открытие «" + applicationName.apply(action.substring(4)) + "»";
        if (action.startsWith("call:")) return "Набор номера: " + applicationName.apply(action);
        if (action.startsWith("split:")) {
            String[] split = action.substring(6).split(",");
            return split.length < 2
                    ? "Открытие сплита"
                    : "Открытие сплита: "
                            + applicationName.apply(split[0])
                            + " + "
                            + applicationName.apply(split[1]);
        }
        if (action.startsWith("can:")) return "CAN-команда отправлена";
        switch (action) {
            case "wash":
                return "Режим мойки: запуск";
            case "open_voyahtune":
                return "Открытие VoyahTune";
            case "open_carplay":
                return "Открытие CarPlay";
            case "system_back":
                return "Назад";
            default:
                return action;
        }
    }

    private static String mode(String value) {
        switch (value) {
            case "EV":
                return "Electric";
            case "REV":
                return "Fuel";
            case "SREV":
                return "Save";
            case "ECO":
                return "Eco";
            case "COMFORT":
                return "Comfort";
            case "SPORT":
                return "Sport";
            case "OUTING":
                return "Outing";
            case "SNOW":
                return "Snow";
            case "INDIVIDUAL":
                return "Individual";
            case "LOW":
                return "низкая";
            case "MEDIUM":
                return "стандартная";
            case "HIGH":
                return "высокая";
            case "off":
                return "выключение";
            case "low":
                return "ближний свет";
            case "auto":
                return "автоматический режим";
            case "parking":
                return "габариты";
            default:
                return value;
        }
    }

    static String sequence(List<ActionResult> results, Function<String, String> applicationName) {
        long accepted = results.stream().filter(result -> result.accepted).count();
        ActionResult failure =
                results.stream().filter(result -> !result.accepted).findFirst().orElse(null);
        if (failure != null) {
            return results.size() == 1
                    ? failure.error
                    : "Отправлено "
                            + accepted
                            + " из "
                            + results.size()
                            + " действий. "
                            + failure.error;
        }
        String text =
                results.stream()
                        .map(result -> action(result.action, applicationName))
                        .collect(Collectors.joining("; "));
        return text.length() <= 140 ? text : "Действия кнопки отправлены: " + accepted;
    }

    static String scenario(String name, int accepted, int total) {
        if (accepted == total) return "Сценарий «" + name + "» выполнен";
        if (accepted == 0) return "Не удалось выполнить сценарий «" + name + "»";
        return "Сценарий «" + name + "»: выполнено " + accepted + " из " + total + " действий";
    }

    private ActionNoticeText() {}
}
