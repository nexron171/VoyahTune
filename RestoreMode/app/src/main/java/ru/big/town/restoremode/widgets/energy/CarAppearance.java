package ru.big.town.restoremode.widgets.energy;

import android.content.SharedPreferences;

public final class CarAppearance {
    // Keep the stored key compatible with existing installations.
    public static final String COLOR_PREFERENCE = "energyCarColor";
    public static final String DEFAULT_COLOR = "burgundy";
    public static final String[] COLORS = {
        "black", "white", "dark_gray", "dark_green", "burgundy", "gold_bronze", "sage_green"
    };
    public static final String[] COLOR_NAMES = {
        "Чёрный",
        "Белый",
        "Тёмно-серый",
        "Тёмно-зелёный",
        "Бургунди",
        "Золотисто-бронзовый",
        "Серо-зелёный"
    };

    private CarAppearance() {}

    public static String savedColor(SharedPreferences preferences) {
        return normalizeColor(preferences.getString(COLOR_PREFERENCE, DEFAULT_COLOR));
    }

    public static String normalizeColor(String value) {
        for (String color : COLORS) {
            if (color.equals(value)) {
                return color;
            }
        }
        return DEFAULT_COLOR;
    }
}
