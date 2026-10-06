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

    static final String SUSPENSION_WIDGET_ID = "suspensionWidget";
    static final int SUSPENSION_DEFAULT_WIDTH = 4;
    static final int SUSPENSION_DEFAULT_HEIGHT = 3;

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
        int value=prefs.getInt(WIDTH_KEY_PREFIX + widgetId, defaultValue);
        if (SystemWidgetLayout.isWidget(widgetId)) return SystemWidgetLayout.width(widgetId, value);
        return EnergyWidgetLayout.isWidget(widgetId)?EnergyWidgetLayout.width(widgetId,value):AppWidgetStore.clampWidth(value);
    }

    static int height(SharedPreferences prefs, String widgetId, int defaultValue) {
        if (prefs == null || widgetId == null) return defaultValue;
        if (SystemWidgetLayout.isWidget(widgetId)) return 1;
        int value=prefs.getInt(HEIGHT_KEY_PREFIX + widgetId, defaultValue);
        return EnergyWidgetLayout.isWidget(widgetId)?EnergyWidgetLayout.height(widgetId,
                width(prefs,widgetId,8),value):AppWidgetStore.clampHeight(value);
    }

    static void setWidth(SharedPreferences prefs, String widgetId, int value) {
        if (prefs == null || widgetId == null) return;
        if (SystemWidgetLayout.isWidget(widgetId)) {
            prefs.edit().putInt(WIDTH_KEY_PREFIX + widgetId, SystemWidgetLayout.width(widgetId, value))
                    .putInt(HEIGHT_KEY_PREFIX + widgetId, 1).apply();
            return;
        }
        int columns=EnergyWidgetLayout.isWidget(widgetId)?EnergyWidgetLayout.width(widgetId,value):AppWidgetStore.clampWidth(value);
        SharedPreferences.Editor editor=prefs.edit().putInt(WIDTH_KEY_PREFIX + widgetId,columns);
        if (EnergyWidgetLayout.isWidget(widgetId)) {
            int rows=prefs.getInt(HEIGHT_KEY_PREFIX + widgetId,EnergyWidgetLayout.minHeight(widgetId));
            editor.putInt(HEIGHT_KEY_PREFIX + widgetId,EnergyWidgetLayout.height(widgetId,columns,rows));
        }
        editor.apply();
    }

    static void setHeight(SharedPreferences prefs, String widgetId, int value) {
        if (prefs == null || widgetId == null) return;
        prefs.edit().putInt(HEIGHT_KEY_PREFIX + widgetId,
                SystemWidgetLayout.isWidget(widgetId) ? 1 : EnergyWidgetLayout.isWidget(widgetId)?EnergyWidgetLayout.height(widgetId,
                        width(prefs,widgetId,8),value):AppWidgetStore.clampHeight(value)).apply();
    }
}
