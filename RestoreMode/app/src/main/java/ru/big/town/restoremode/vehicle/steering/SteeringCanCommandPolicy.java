package ru.big.town.restoremode.vehicle.steering;

import java.util.Locale;

/** Форматирование и проверка одной 10-байтной CAN-команды для действия кнопки руля. */
public final class SteeringCanCommandPolicy {
    public static final int HEX_LENGTH = 20;

    private SteeringCanCommandPolicy() {}

    public static String compact(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
    }

    public static String format(String input) {
        String compact = compact(input);
        StringBuilder formatted = new StringBuilder(compact.length() + compact.length() / 2);
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0 && i % 2 == 0) {
                formatted.append(' ');
            }
            formatted.append(compact.charAt(i));
        }
        return formatted.toString();
    }

    public static boolean isValid(String input) {
        return compact(input).length() == HEX_LENGTH;
    }

    public static String actionId(String input) {
        String command = compact(input);
        if (command.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("CAN command must contain exactly 10 bytes");
        }
        return "can:" + command;
    }
}
