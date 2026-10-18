package ru.big.town.restoremode.dashboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Display;
import android.view.WindowManager;

/**
 * Layout profile of a physical dashboard. Driver keeps the historical preferences and widget IDs;
 * passenger uses a separate composition file so both screens can keep independent tiles.
 */
public final class DashboardProfile {
    public static final String SETTINGS_PREFERENCES = "DrivePreferences";
    public static final String PASSENGER_PREFERENCES = "DrivePreferencesPassenger";
    private static final String PASSENGER_WIDGET_PREFIX = "passenger-";

    private final boolean passenger;

    private DashboardProfile(boolean passenger) {
        this.passenger = passenger;
    }

    public static DashboardProfile driver() {
        return new DashboardProfile(false);
    }

    public static DashboardProfile passenger() {
        return new DashboardProfile(true);
    }

    public static DashboardProfile forDisplay(int displayId) {
        return displayId == 1 ? passenger() : driver();
    }

    public static int displayId(Context context) {
        try {
            WindowManager manager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) {
                return Display.DEFAULT_DISPLAY;
            }
            int displayId = manager.getDefaultDisplay().getDisplayId();
            return displayId == 1 ? 1 : 0;
        } catch (RuntimeException ignored) {
            return Display.DEFAULT_DISPLAY;
        }
    }

    public boolean isPassenger() {
        return passenger;
    }

    /** Global settings shared by both screens and mirrored to Native. */
    public SharedPreferences settingsPreferences(Context context) {
        return context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Screen-scoped tile order, visibility, sizes and app-widget lists. */
    public SharedPreferences layoutPreferences(Context context) {
        return context.getSharedPreferences(
                passenger ? PASSENGER_PREFERENCES : SETTINGS_PREFERENCES,
                Context.MODE_PRIVATE);
    }

    public String widgetIdPrefix() {
        return passenger ? PASSENGER_WIDGET_PREFIX : "";
    }

    public boolean ownsWidgetId(String widgetId) {
        if (widgetId == null || widgetId.isEmpty()) {
            return false;
        }
        boolean passengerId = widgetId.startsWith(PASSENGER_WIDGET_PREFIX);
        return passenger == passengerId;
    }

    /** Passenger cards stay hidden until the user enables them in its own settings section. */
    public boolean defaultVisible(boolean driverDefault) {
        return passenger ? false : driverDefault;
    }
}
