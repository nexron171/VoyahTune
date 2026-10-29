package ru.voyahtune.agent.policy;

import java.util.Locale;

public final class KeyboardPolicy {
    public enum ToggleIcon {
        NONE, RUSSIAN, RUSSIAN_WHITE, ENGLISH_WHITE, SHIFT_LOWER_WHITE, SHIFT_UPPER_WHITE, SHIFT_TEMP_WHITE
    }

    private KeyboardPolicy() {}

    public static int englishMode(int requested) {
        switch (requested) {
            case 33554432:
            case 36765696:
            case 34668544:
            case 35717120:
            case 33685504:
            case 33751040:
                return requested;
            default:
                return 36765696;
        }
    }

    public static boolean russianLetter(int code) {
        return code >= 10001 && code <= 10007;
    }

    public static boolean letter(int code) {
        return code >= 29 && code <= 54 || russianLetter(code);
    }

    public static String letterCase(String text, boolean uppercase) {
        return uppercase ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
    }

    public static ToggleIcon toggleIcon(int code, int state, boolean white, boolean customAvailable) {
        if (white && code == 0) {
            switch (state) {
                case 2: return ToggleIcon.SHIFT_LOWER_WHITE;
                case 3: return ToggleIcon.SHIFT_UPPER_WHITE;
                case 16: return ToggleIcon.SHIFT_TEMP_WHITE;
                default: return ToggleIcon.NONE;
            }
        }
        if (code != -2 || state != 2 && state != 3 && state != 16) return ToggleIcon.NONE;
        if (white) return customAvailable ? ToggleIcon.RUSSIAN_WHITE : ToggleIcon.ENGLISH_WHITE;
        return customAvailable ? ToggleIcon.RUSSIAN : ToggleIcon.NONE;
    }
}
