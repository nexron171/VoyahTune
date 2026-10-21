package ru.big.town.restoremode.carplay;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.DiPlaySessionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

/** Coordinates the feature switch across launcher entries, session hosts and both dashboards. */
public final class CarPlayFeature {
    public static final String ACTION_CHANGED = CarPlayTarget.PACKAGE + ".CARPLAY_ENABLED_CHANGED";
    private static final Set<Activity> activities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final String[] ENTRY_COMPONENTS = {
        CarPlayTarget.LAUNCHER, CarPlayTarget.ENTRY, CarPlayTarget.ACTIVITY, CarPlayTarget.PROJECTION
    };

    private CarPlayFeature() {}

    public static boolean isEnabled(Context context) {
        return CarPlaySettingsAccess.isEnabled(context);
    }

    public static void initialize(Application application) {
        syncComponents(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (isCarPlayActivity(activity)) {
                    activities.add(activity);
                }
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (isCarPlayActivity(activity) && !isEnabled(activity)) {
                    activity.finishAndRemoveTask();
                }
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                activities.remove(activity);
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        });
    }

    public static void setEnabled(Context context, boolean enabled) {
        Context application = context.getApplicationContext();
        CarPlaySettingsAccess.setEnabled(application, enabled);
        syncComponents(application);
        if (!enabled) {
            disableWidgets(application, DashboardProfile.driver());
            disableWidgets(application, DashboardProfile.passenger());
            clearDockAndSiri(application);
            CarPlaySettingsAccess.disconnect(() -> new Handler(Looper.getMainLooper()).post(() -> {
                // A quick re-enable must not close a newly opened session host.
                if (isEnabled(application)) {
                    return;
                }
                for (Activity activity : new ArrayList<>(activities)) {
                    if (!activity.isDestroyed()) {
                        activity.finishAndRemoveTask();
                    }
                }
                application.stopService(new Intent(application, DiPlaySessionService.class));
            }));
        }
        SharedPreferences preferences = DashboardProfile.driver().settingsPreferences(application);
        SplitConfigSync.pushDock(application, preferences);
        SplitConfigSync.pushSteering(application, preferences);
        application.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(application.getPackageName()));
    }

    private static boolean isCarPlayActivity(Activity activity) {
        String name = activity.getClass().getName();
        return name.startsWith("com.shilapi.xcertplay.") || CarPlayTarget.LAUNCHER.equals(name);
    }

    private static void syncComponents(Context context) {
        int state = isEnabled(context) ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager manager = context.getPackageManager();
        for (String name : ENTRY_COMPONENTS) {
            ComponentName component = new ComponentName(context, name);
            if (manager.getComponentEnabledSetting(component) != state) {
                manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
            }
        }
    }

    private static void disableWidgets(Context context, DashboardProfile profile) {
        SharedPreferences layout = profile.layoutPreferences(context);
        ru.big.town.restoremode.widgets.apps.AppWidgetSessionStore.forgetPackage(
                context, layout, CarPlayTarget.ID);
        AppWidgetStore.removeCarPlay(layout);
        java.util.List<String> shortcuts = AppShortcutStore.load(layout);
        if (shortcuts.removeIf(CarPlayTarget::isCarPlay)) {
            AppShortcutStore.save(layout, shortcuts);
        }
        TileOrderStore.sync(layout, profile.settingsPreferences(context),
                context.getPackageManager(), profile.isPassenger());
    }

    private static void clearDockAndSiri(Context context) {
        SharedPreferences preferences = DashboardProfile.driver().settingsPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (int slot = 1; slot <= 2; slot++) {
            String key = "dockOverride" + slot;
            if (CarPlayTarget.isCarPlay(preferences.getString(key, ""))) {
                editor.remove(key)
                        .remove(key + "Label")
                        .remove(key + "LongAction")
                        .remove(key + "Split")
                        .remove(key + "SplitLabel");
            }
        }
        for (String button : new String[] {"Star", "Dvr", "Voice", "Phone"}) {
            for (String press : new String[] {"Short", "Long"}) {
                String key = "steer" + button + press;
                java.util.List<String> actions = SteeringActionStore.load(preferences, key);
                if (actions.removeIf("open_carplay"::equals)) {
                    editor.putString(key, SteeringActionStore.encode(actions));
                }
            }
        }
        editor.putBoolean(VoiceSteeringPolicy.SIRI_ENABLED, false).apply();
    }
}
