package ru.big.town.restoremode.voice.commands;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit numeric phrases keep unsupported targets out of fuzzy matching. */
final class VoiceClimateCommands {
    private static final String[] SMALL = {
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"
    };
    private static final String[] TEENS = {
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
    };

    private VoiceClimateCommands() {}

    static void addTo(List<VoiceCommandCatalog.Command> commands) {
        binary(commands, CarPlayClimateProtocol.POWER, "Климат", "климат");
        binary(
                commands,
                CarPlayClimateProtocol.COMPRESSOR,
                "Кондиционер",
                "кондиционер",
                "климат кондиционер");
        binary(
                commands,
                CarPlayClimateProtocol.AUTO,
                "Автоматический климат",
                "автоматический климат",
                "климат авто");
        binary(
                commands,
                CarPlayClimateProtocol.SYNC,
                "Синхронизация климата",
                "синхронизацию климата",
                "климат синхронизация");
        binary(
                commands,
                CarPlayClimateProtocol.RECIRCULATION,
                "Рециркуляция",
                "рециркуляцию",
                "климат рециркуляция");
        add(
                commands,
                CarPlayClimateProtocol.RECIRCULATION_AUTO,
                "1",
                "Рециркуляция: автоматический режим",
                "рециркуляция авто",
                "включи рециркуляцию авто",
                "климат рециркуляция авто",
                "автоматическая рециркуляция",
                "включи автоматическую рециркуляцию");
        temperature(commands, "temperature", "");
        temperature(commands, CarPlayClimateProtocol.DRIVER_TEMPERATURE, "водителя");
        temperature(commands, CarPlayClimateProtocol.PASSENGER_TEMPERATURE, "пассажира");
        for (int level = 0; level <= 7; level++) {
            add(
                    commands,
                    CarPlayClimateProtocol.FAN,
                    Integer.toString(level),
                    "Скорость обдува: " + level,
                    "скорость обдува " + level,
                    "скорость обдува " + SMALL[level],
                    "климат скорость " + level,
                    "климат скорость " + SMALL[level],
                    "климат скорость обдува " + SMALL[level]);
        }
        String[] directions = {
            "в лицо", "в лицо и ноги", "в ноги", "на стекло и ноги", "на стекло"
        };
        for (int mode = 0; mode < directions.length; mode++) {
            add(
                    commands,
                    CarPlayClimateProtocol.VENT_MODE,
                    Integer.toString(mode),
                    "Обдув " + directions[mode],
                    "обдув " + directions[mode],
                    "климат " + directions[mode],
                    "климат обдув " + directions[mode]);
        }
        VoiceFragranceCommands.addTo(commands);
    }

    private static void temperature(
            List<VoiceCommandCatalog.Command> commands, String field, String side) {
        for (int halfDegrees = 31; halfDegrees <= 61; halfDegrees++) {
            int degrees = halfDegrees / 2;
            boolean half = halfDegrees % 2 != 0;
            String value = Integer.toString(degrees) + (half ? ".5" : "");
            String spoken = number(degrees) + (half ? " с половиной" : "");
            String zone = side.isEmpty() ? "" : " " + side;
            String unit =
                    half
                            ? "градуса"
                            : degrees == 21
                                    ? "градус"
                                    : degrees >= 22 && degrees <= 24 ? "градуса" : "градусов";
            List<String> phrases = new ArrayList<>();
            for (String prefix :
                    new String[] {
                        "температура" + zone, "климат" + zone, "климат температура" + zone
                    }) {
                Collections.addAll(
                        phrases,
                        prefix + " " + value,
                        prefix + " " + spoken,
                        prefix + " " + value + " " + unit,
                        prefix + " " + spoken + " " + unit);
                phrases.add(prefix + " " + spoken + " градуса");
            }
            add(
                    commands,
                    field,
                    value,
                    "Температура " + (side.isEmpty() ? "обеих зон" : side) + ": " + value + " °C",
                    phrases.toArray(new String[0]));
        }
    }

    private static String number(int value) {
        if (value < 20) return TEENS[value - 15];
        String tens = value < 30 ? "двадцать" : "тридцать";
        return value % 10 == 0 ? tens : tens + " " + SMALL[value % 10];
    }

    private static void binary(
            List<VoiceCommandCatalog.Command> commands,
            String field,
            String title,
            String... names) {
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (String name : names) {
            Collections.addAll(on, "включи " + name, "включить " + name);
            Collections.addAll(off, "выключи " + name, "отключи " + name, "выключить " + name);
        }
        add(commands, field, "1", title + ": включить", on.toArray(new String[0]));
        add(commands, field, "0", title + ": выключить", off.toArray(new String[0]));
    }

    private static void add(
            List<VoiceCommandCatalog.Command> commands,
            String field,
            String value,
            String title,
            String... phrases) {
        VoiceCommandCatalog.add(commands, "climate:" + field + ":" + value, title, phrases);
    }
}
