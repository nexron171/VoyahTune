package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/** Restores the service switch from RestoreMode, with NativePrefs as an offline fallback. */
final class AutoLightSettings {
    interface Runtime {
        Boolean saved();
        boolean cached();
        void cache(boolean enabled);
        void apply(boolean enabled);
    }

    static synchronized void restore(Runtime runtime) {
        Boolean saved;
        try { saved = runtime.saved(); }
        catch (RuntimeException unavailable) { saved = null; }
        boolean enabled = saved != null ? saved : runtime.cached();
        runtime.cache(enabled);
        runtime.apply(enabled); // An explicit false must stop a previously running service too.
    }

    /** Call on an ApplyEngine worker: the provider query must never block Android's main thread. */
    static void restore(Context context) {
        restore(runtime(context));
    }

    /** Share the restore lock so an older provider read cannot apply after a newer user command. */
    static synchronized void set(Context context, boolean enabled) {
        MainActivity.persistSavedToggle(context, "autoLight", enabled);
        Runtime runtime = runtime(context);
        runtime.cache(enabled);
        runtime.apply(enabled);
    }

    /** Reconcile scenario sensor demand without querying or changing RestoreMode preferences. */
    static synchronized void refreshObservation(Context context) {
        Runtime runtime = runtime(context);
        runtime.apply(runtime.cached());
    }

    private static Runtime runtime(Context context) {
        return new Runtime() {
            @Override public Boolean saved() {
                try (Cursor cursor = context.getContentResolver().query(Uri.parse(
                        "content://ru.big.town.restoremode.restoremodecontentprovider/"),
                        null, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) return null;
                    int column = cursor.getColumnIndex("autoLight");
                    if (column < 0 || cursor.isNull(column)) return null;
                    int value = cursor.getInt(column);
                    return value == 0 ? Boolean.FALSE : value == 1 ? Boolean.TRUE : null;
                }
            }

            @Override public boolean cached() {
                return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                        .getBoolean("autoLight", false);
            }

            @Override public void cache(boolean enabled) {
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("autoLight", enabled).apply();
            }

            @Override public void apply(boolean enabled) {
                boolean scenarioNeedsLight = false;
                for (ScenarioDefinition scenario : ScenarioDefinition.parse(ScenarioConfigReceiver.loadPersisted(context))) {
                    if (scenario.enabled && scenario.usesLight()) { scenarioNeedsLight = true; break; }
                }
                LightSensorService.setObserveOnly(context, !enabled && scenarioNeedsLight);
                Intent service = new Intent(context, LightSensorService.class);
                if (enabled || scenarioNeedsLight) {
                    if (context.startForegroundService(service) == null) {
                        throw new IllegalStateException("Auto light service start was not accepted");
                    }
                } else context.stopService(service);
            }
        };
    }
}
