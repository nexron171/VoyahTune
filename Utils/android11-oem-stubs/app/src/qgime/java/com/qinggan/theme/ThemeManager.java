package com.qinggan.theme;

import android.content.Context;

/** Synthetic theme API used only while keyboard hooks resolve reflected fields. */
public class ThemeManager {
    public static String DEFAULT_THEME_TITLE2 = "stub-white";

    private static final ThemeManager INSTANCE = new ThemeManager();

    public static ThemeManager getInstance(Context context) {
        return INSTANCE;
    }

    public String getCurrentThemeTitle() {
        return DEFAULT_THEME_TITLE2;
    }
}
