package ru.big.town.anative;

import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Collections;
import java.util.Set;

/** Durable event history; service recreation is not a physical wake boundary. */
final class ScenarioTriggerPersistence {
    private static final String FILE = "ScenarioTriggerHistory";
    private static final String CONSUMED = "consumed";
    private static final String SLEEPING = "sleeping";
    private static final String ENTRY_REQUESTED = "entryRequested";
    private static final Object LOCK = new Object();

    private ScenarioTriggerPersistence() {}

    private static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int boot =
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (preferences.getInt("boot", -1) != boot) {
            preferences.edit().clear().putInt("boot", boot).commit();
        }
        return preferences;
    }

    static void sleep(Context context, int powerState) {
        synchronized (LOCK) {
            SharedPreferences.Editor change =
                    preferences(context).edit().putBoolean(SLEEPING, true);
            if (powerState == CarPowerStateListener.SUSPEND_ENTER
                    || powerState == CarPowerStateListener.SHUTDOWN_ENTER) {
                // CarPower can still cancel while waiting for the vehicle to finish shutdown.
                change.putBoolean(ENTRY_REQUESTED, true);
            }
            change.commit();
        }
    }

    static void wake(Context context, int powerState) {
        if (powerState == CarPowerStateListener.WAIT_FOR_VHAL) return;
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.getBoolean(SLEEPING, false)) return;
            SharedPreferences.Editor change =
                    preferences.edit().remove(ENTRY_REQUESTED).putBoolean(SLEEPING, false);
            if (powerState == CarPowerStateListener.SUSPEND_EXIT
                    || (powerState == CarPowerStateListener.ON
                            && preferences.getBoolean(ENTRY_REQUESTED, false))) {
                change.remove(CONSUMED);
            }
            change.commit();
        }
    }

    static boolean accept(Context context, String scenarioId, ScenarioDefinition.Trigger trigger) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (preferences.getBoolean(SLEEPING, false)) return false;
            ScenarioTriggerHistory history =
                    new ScenarioTriggerHistory(
                            new ScenarioTriggerHistory.Store() {
                                @Override
                                public Set<String> read() {
                                    return preferences.getStringSet(
                                            CONSUMED, Collections.emptySet());
                                }

                                @Override
                                public void write(Set<String> consumed) {
                                    preferences.edit().putStringSet(CONSUMED, consumed).commit();
                                }
                            });
            return history.accept(scenarioId, trigger);
        }
    }
}
