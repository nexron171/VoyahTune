package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Collections;
import java.util.Set;

/** Durable event history; service recreation is not a physical wake boundary. */
final class ScenarioTriggerPersistence {
    private static final String FILE = "ScenarioTriggerHistory";
    private static final String CONSUMED = "consumed";
    private static final Object LOCK = new Object();

    private ScenarioTriggerPersistence() {}

    private static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (preferences.getInt("boot", -1) != boot) {
            preferences.edit().clear().putInt("boot", boot).commit();
        }
        return preferences;
    }

    static void sleep(Context context) {
        synchronized (LOCK) {
            preferences(context).edit().remove(CONSUMED).putBoolean("sleeping", true).commit();
        }
    }

    static void wake(Context context) {
        synchronized (LOCK) {
            preferences(context).edit().putBoolean("sleeping", false).commit();
        }
    }

    static boolean accept(Context context, String scenarioId, ScenarioDefinition.Trigger trigger) {
        synchronized (LOCK) {
            SharedPreferences preferences = preferences(context);
            if (preferences.getBoolean("sleeping", false)) return false;
            return new ScenarioTriggerHistory(new ScenarioTriggerHistory.Store() {
                @Override
                public Set<String> read() {
                    return preferences.getStringSet(CONSUMED, Collections.emptySet());
                }

                @Override
                public void write(Set<String> consumed) {
                    preferences.edit().putStringSet(CONSUMED, consumed).commit();
                }
            }).accept(scenarioId, trigger);
        }
    }
}
