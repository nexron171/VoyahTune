package ru.big.town.restoremode;

import android.content.SharedPreferences;

/**
 * Размеры плиток главного экрана в ячейках smart-grid (12 колонок × 5 рядов), изменённые
 * пользователем в «Дополнительно». Плитка без переопределения получает дефолт из
 * {@code MainActivity.getWidgetDimensions()}.
 */
class TileSizeStore {
    /** Плитка «Быстрый запуск»: id в TileOrderStore и размер по умолчанию (2×3 ячейки). */
    static final String LAUNCH_APPS_WIDGET_ID = "launchAppsWidget";
    static final int LAUNCH_APPS_DEFAULT_WIDTH = 2;
    static final int LAUNCH_APPS_DEFAULT_HEIGHT = 3;

    private static final String WIDTH_KEY_PREFIX = "tileWidth_";
    private static final String HEIGHT_KEY_PREFIX = "tileHeight_";

    private TileSizeStore() {}

    /** Размер плитки {ширина, высота}: пользовательское значение либо дефолт. */
    static int[] dimensions(SharedPreferences prefs, String widgetId,
                            int defaultWidth, int defaultHeight) {
        return new int[]{width(prefs, widgetId, defaultWidth),
                         height(prefs, widgetId, defaultHeight)};
    }

    static int width(SharedPreferences prefs, String widgetId, int defaultValue) {
        if (prefs == null || widgetId == null) return defaultValue;
        return AppWidgetStore.clampWidth(prefs.getInt(WIDTH_KEY_PREFIX + widgetId, defaultValue));
    }

    static int height(SharedPreferences prefs, String widgetId, int defaultValue) {
        if (prefs == null || widgetId == null) return defaultValue;
        return AppWidgetStore.clampHeight(prefs.getInt(HEIGHT_KEY_PREFIX + widgetId, defaultValue));
    }

    static void setWidth(SharedPreferences prefs, String widgetId, int value) {
        if (prefs == null || widgetId == null) return;
        prefs.edit().putInt(WIDTH_KEY_PREFIX + widgetId,
                AppWidgetStore.clampWidth(value)).apply();
    }

    static void setHeight(SharedPreferences prefs, String widgetId, int value) {
        if (prefs == null || widgetId == null) return;
        prefs.edit().putInt(HEIGHT_KEY_PREFIX + widgetId,
                AppWidgetStore.clampHeight(value)).apply();
    }
}
