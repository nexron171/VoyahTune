package ru.big.town.restoremode.dashboard.tiles;

import android.content.SharedPreferences;

import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.clock.ClockWidgetView;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetLayout;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;
import ru.big.town.restoremode.widgets.media.MediaWidgetLayout;
import ru.big.town.restoremode.widgets.radio.RadioWidgetLayout;
import ru.big.town.restoremode.widgets.system.SystemWidgetLayout;

/**
 * Размеры плиток главного экрана в ячейках smart-grid (12 колонок × 5 рядов), изменённые
 * пользователем в «Дополнительно». Плитка без переопределения получает дефолт из {@code
 * MainActivity.getWidgetDimensions()}.
 */
public class TileSizeStore {
    /** Плитка «Быстрый запуск»: id в TileOrderStore и размер по умолчанию (2×3 ячейки). */
    public static final String LAUNCH_APPS_WIDGET_ID = "launchAppsWidget";

    public static final int LAUNCH_APPS_DEFAULT_WIDTH = 2;
    public static final int LAUNCH_APPS_DEFAULT_HEIGHT = 3;

    public static final String SUSPENSION_WIDGET_ID = "suspensionWidget";
    public static final int SUSPENSION_DEFAULT_WIDTH = 4;
    public static final int SUSPENSION_DEFAULT_HEIGHT = 3;

    private static final String WIDTH_KEY_PREFIX = "tileWidth_";
    private static final String HEIGHT_KEY_PREFIX = "tileHeight_";

    private TileSizeStore() {}

    /** Размер плитки {ширина, высота}: пользовательское значение либо дефолт. */
    public static int[] dimensions(
            SharedPreferences prefs, String widgetId, int defaultWidth, int defaultHeight) {
        int columns = width(prefs, widgetId, defaultWidth);
        int rows =
                prefs == null || widgetId == null
                        ? defaultHeight
                        : clampHeight(
                                widgetId,
                                columns,
                                prefs.getInt(HEIGHT_KEY_PREFIX + widgetId, defaultHeight));
        return new int[] {columns, rows};
    }

    public static int width(SharedPreferences prefs, String widgetId, int defaultValue) {
        if (prefs == null || widgetId == null) {
            return defaultValue;
        }
        return clampWidth(widgetId, prefs.getInt(WIDTH_KEY_PREFIX + widgetId, defaultValue));
    }

    private static int clampWidth(String widgetId, int value) {
        if (ClockWidgetView.ID.equals(widgetId)) {
            return Math.max(1, Math.min(5, value));
        }
        if (RadioWidgetLayout.ID.equals(widgetId)) {
            return RadioWidgetLayout.width(value);
        }
        if (HeadlightWidgetLayout.ID.equals(widgetId)) {
            return HeadlightWidgetLayout.width(value);
        }
        if (SystemWidgetLayout.isWidget(widgetId)) {
            return SystemWidgetLayout.width(widgetId, value);
        }
        if (MediaWidgetLayout.isWidget(widgetId)) {
            return MediaWidgetLayout.width(value);
        }
        return EnergyWidgetLayout.isWidget(widgetId)
                ? EnergyWidgetLayout.width(widgetId, value)
                : AppWidgetStore.clampWidth(value);
    }

    public static int height(SharedPreferences prefs, String widgetId, int defaultValue) {
        if (prefs == null || widgetId == null) {
            return defaultValue;
        }
        int defaultColumns =
                MediaWidgetLayout.isWidget(widgetId)
                        ? MediaWidgetLayout.DEFAULT_WIDTH
                        : HeadlightWidgetLayout.ID.equals(widgetId)
                                ? HeadlightWidgetLayout.DEFAULT_WIDTH
                                : 8;
        return clampHeight(
                widgetId,
                width(prefs, widgetId, defaultColumns),
                prefs.getInt(HEIGHT_KEY_PREFIX + widgetId, defaultValue));
    }

    private static int clampHeight(String widgetId, int columns, int value) {
        if (RadioWidgetLayout.ID.equals(widgetId)) {
            return RadioWidgetLayout.height(value);
        }
        if (HeadlightWidgetLayout.ID.equals(widgetId)) {
            return HeadlightWidgetLayout.size(columns, value).height;
        }
        if (SystemWidgetLayout.isWidget(widgetId)) {
            return 1;
        }
        if (MediaWidgetLayout.isWidget(widgetId)) {
            return MediaWidgetLayout.height(columns, value);
        }
        return EnergyWidgetLayout.isWidget(widgetId)
                ? EnergyWidgetLayout.height(widgetId, columns, value)
                : AppWidgetStore.clampHeight(value);
    }

    public static boolean supportsSize(String widgetId, int columns, int rows) {
        return columns == clampWidth(widgetId, columns)
                && rows == clampHeight(widgetId, columns, rows);
    }

    public static void setSize(
            SharedPreferences preferences, String widgetId, int columns, int rows) {
        if (widgetId != null && supportsSize(widgetId, columns, rows)) {
            preferences
                    .edit()
                    .putInt(WIDTH_KEY_PREFIX + widgetId, columns)
                    .putInt(HEIGHT_KEY_PREFIX + widgetId, rows)
                    .apply();
        }
    }

    public static void setWidth(SharedPreferences prefs, String widgetId, int value) {
        if (ClockWidgetView.ID.equals(widgetId)) {
            value = Math.max(1, Math.min(5, value));
        }
        if (prefs == null || widgetId == null) {
            return;
        }
        if (RadioWidgetLayout.ID.equals(widgetId)) {
            prefs.edit()
                    .putInt(WIDTH_KEY_PREFIX + widgetId, RadioWidgetLayout.width(value))
                    .apply();
            return;
        }
        if (HeadlightWidgetLayout.ID.equals(widgetId)) {
            setHeadlightSize(
                    prefs,
                    HeadlightWidgetLayout.size(
                            value,
                            prefs.getInt(
                                    HEIGHT_KEY_PREFIX + widgetId,
                                    HeadlightWidgetLayout.DEFAULT_HEIGHT)));
            return;
        }
        if (SystemWidgetLayout.isWidget(widgetId)) {
            prefs.edit()
                    .putInt(WIDTH_KEY_PREFIX + widgetId, SystemWidgetLayout.width(widgetId, value))
                    .putInt(HEIGHT_KEY_PREFIX + widgetId, 1)
                    .apply();
            return;
        }
        int columns =
                EnergyWidgetLayout.isWidget(widgetId)
                        ? EnergyWidgetLayout.width(widgetId, value)
                        : MediaWidgetLayout.isWidget(widgetId)
                                ? MediaWidgetLayout.width(value)
                                : AppWidgetStore.clampWidth(value);
        SharedPreferences.Editor editor = prefs.edit().putInt(WIDTH_KEY_PREFIX + widgetId, columns);
        if (MediaWidgetLayout.isWidget(widgetId)) {
            int rows = prefs.getInt(HEIGHT_KEY_PREFIX + widgetId, 1);
            editor.putInt(HEIGHT_KEY_PREFIX + widgetId, MediaWidgetLayout.height(columns, rows));
        }
        if (EnergyWidgetLayout.isWidget(widgetId)) {
            int rows =
                    prefs.getInt(
                            HEIGHT_KEY_PREFIX + widgetId, EnergyWidgetLayout.minHeight(widgetId));
            editor.putInt(
                    HEIGHT_KEY_PREFIX + widgetId,
                    EnergyWidgetLayout.height(widgetId, columns, rows));
        }
        editor.apply();
    }

    public static void setHeight(SharedPreferences prefs, String widgetId, int value) {
        if (prefs == null || widgetId == null) {
            return;
        }
        if (RadioWidgetLayout.ID.equals(widgetId)) {
            prefs.edit()
                    .putInt(HEIGHT_KEY_PREFIX + widgetId, RadioWidgetLayout.height(value))
                    .apply();
            return;
        }
        if (HeadlightWidgetLayout.ID.equals(widgetId)) {
            setHeadlightSize(
                    prefs,
                    HeadlightWidgetLayout.size(
                            width(prefs, widgetId, HeadlightWidgetLayout.DEFAULT_WIDTH), value));
            return;
        }
        prefs.edit()
                .putInt(
                        HEIGHT_KEY_PREFIX + widgetId,
                        SystemWidgetLayout.isWidget(widgetId)
                                ? 1
                                : MediaWidgetLayout.isWidget(widgetId)
                                        ? MediaWidgetLayout.height(
                                                width(
                                                        prefs,
                                                        widgetId,
                                                        MediaWidgetLayout.DEFAULT_WIDTH),
                                                value)
                                        : EnergyWidgetLayout.isWidget(widgetId)
                                                ? EnergyWidgetLayout.height(
                                                        widgetId, width(prefs, widgetId, 8), value)
                                                : AppWidgetStore.clampHeight(value))
                .apply();
    }

    public static void setHeadlightSize(
            SharedPreferences preferences, HeadlightWidgetLayout.Size size) {
        if (preferences == null || size == null) {
            return;
        }
        preferences
                .edit()
                .putInt(WIDTH_KEY_PREFIX + HeadlightWidgetLayout.ID, size.width)
                .putInt(HEIGHT_KEY_PREFIX + HeadlightWidgetLayout.ID, size.height)
                .apply();
    }
}
