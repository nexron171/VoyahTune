package ru.big.town.restoremode.appearance;

import android.content.SharedPreferences;

import ru.big.town.restoremode.R;

public final class AppearanceSettings {
    public static final String TRANSPARENCY_ENABLED = "widgetTransparencyEnabled";
    public static final String TRANSPARENCY_PERCENT = "widgetTransparencyPercent";
    public static final String BACKGROUND_COLOR = "widgetBackgroundColor";
    public static final String BLUR_ENABLED = "widgetBlurEnabled";
    public static final String BLUR_AMOUNT = "widgetBlurAmount";
    public static final String WALLPAPER = "dashboardWallpaper";
    public static final String TILE_SPACING = "tileSpacingDp";
    public static final String TILE_BORDER = "tileBorderDp";
    public static final String TILE_CORNER_RADIUS = "tileCornerRadiusDp";
    public static final int DEFAULT_BACKGROUND_COLOR = 0xff22252f;
    public static final int MAX_SPACING_DP = 24;
    public static final int MAX_BORDER_DP = 10;
    public static final int DEFAULT_BORDER_DP = 1;
    public static final int MAX_CORNER_RADIUS_DP = 40;
    public static final int DEFAULT_CORNER_RADIUS_DP = 20;

    public final boolean transparencyEnabled;
    public final int transparencyPercent;
    public final int backgroundColor;
    public final boolean blurEnabled;
    public final int blurAmount;
    public final int borderDp;
    public final int cornerRadiusDp;
    public final Wallpaper wallpaper;

    public static final class Wallpaper {
        public static final Wallpaper BLACK = new Wallpaper("black", "Чёрный", 0);
        public static final Wallpaper GREEN = new Wallpaper("green", "Тёмно-зелёный", R.drawable.wallpaper_green);
        public static final Wallpaper GOLD = new Wallpaper("gold", "Золотисто-серый", R.drawable.wallpaper_gold);
        public static final Wallpaper GRAPHITE = new Wallpaper("graphite", "Чёрно-серый", R.drawable.wallpaper_graphite);
        public static final Wallpaper BLUE = new Wallpaper("blue", "Тёмно-синий", R.drawable.wallpaper_blue);
        public static final Wallpaper RED = new Wallpaper("red", "Тёмно-красный", R.drawable.wallpaper_red);
        public static final Wallpaper PURPLE = new Wallpaper("purple", "Тёмно-фиолетовый", R.drawable.wallpaper_purple);
        public static final Wallpaper LILAC = new Wallpaper("lilac", "Тёмно-сиреневый", R.drawable.wallpaper_lilac);
        public static final Wallpaper BURGUNDY = new Wallpaper("burgundy", "Бургунди", R.drawable.wallpaper_burgundy);
        private static final Wallpaper[] BUILT_IN = {
            BLACK, GREEN, GOLD, GRAPHITE, BLUE, RED, PURPLE, LILAC, BURGUNDY
        };

        public final String key;
        public final String title;
        public final int resource;

        Wallpaper(String key, String title, int resource) {
            this.key = key;
            this.title = title;
            this.resource = resource;
        }

        public static Wallpaper find(String key) {
            for (Wallpaper wallpaper : values()) {
                if (wallpaper.key.equals(key)) {
                    return wallpaper;
                }
            }
            return null;
        }

        public static Wallpaper[] values() {
            return BUILT_IN.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Wallpaper && key.equals(((Wallpaper) other).key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    private AppearanceSettings(SharedPreferences preferences) {
        transparencyEnabled = preferences.getBoolean(TRANSPARENCY_ENABLED, false);
        transparencyPercent = bounded(preferences.getInt(TRANSPARENCY_PERCENT, 50), 100);
        backgroundColor = preferences.getInt(BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR) | 0xff000000;
        blurEnabled = preferences.getBoolean(BLUR_ENABLED, false);
        blurAmount = bounded(preferences.getInt(BLUR_AMOUNT, 30), 100);
        borderDp = bounded(preferences.getInt(TILE_BORDER, DEFAULT_BORDER_DP), MAX_BORDER_DP);
        cornerRadiusDp = bounded(
                preferences.getInt(TILE_CORNER_RADIUS, DEFAULT_CORNER_RADIUS_DP), MAX_CORNER_RADIUS_DP);
        Wallpaper selected = WallpaperStore.find(preferences, preferences.getString(WALLPAPER, Wallpaper.BLACK.key));
        wallpaper = selected != null ? selected : Wallpaper.BLACK;
    }

    public static AppearanceSettings load(SharedPreferences preferences) {
        return new AppearanceSettings(preferences);
    }

    public static int spacingDp(SharedPreferences preferences) {
        return bounded(preferences.getInt(TILE_SPACING, 4), MAX_SPACING_DP);
    }

    public int surfaceAlpha() {
        return transparencyEnabled ? Math.round(255 * (100 - transparencyPercent) / 100f) : 255;
    }

    public boolean customBackground() {
        return transparencyEnabled || backgroundColor != DEFAULT_BACKGROUND_COLOR;
    }

    public boolean blursWallpaper() {
        return transparencyEnabled && blurEnabled && blurAmount > 0 && wallpaper != Wallpaper.BLACK;
    }

    private static int bounded(int value, int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }
}
