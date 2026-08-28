package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Event-driven publication of the persisted Dock/steering/DPI/keyboard configuration. Native calls this exact
 * component once when SetModes starts and once per coalesced physical vehicle wake, so no screen
 * needs to be opened and no periodic reader is required.
 */
public final class SavedConfigSyncReceiver extends BroadcastReceiver {
    public static final String ACTION = "ru.big.town.restoremode.SYNC_SAVED_CONFIG";
    private static final String TAG = "SavedConfigSync";
    private static final String PREFS = "DrivePreferences";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            // One-way migration: passenger Air/Seat are OEM controls again. Remove the obsolete
            // picker state as well as clearing its Settings.Global projection in Native, so an
            // older APK cannot resurrect the removed overrides from preserved app data.
            boolean legacyPassengerStateRemoved = prefs.edit()
                    .remove("dockPassengerOverride1")
                    .remove("dockPassengerOverride1Label")
                    .remove("dockPassengerOverride2")
                    .remove("dockPassengerOverride2Label")
                    .commit();
            if (!legacyPassengerStateRemoved) {
                Log.w(TAG, "obsolete passenger dock preferences could not be removed");
            }
            SplitConfigSync.pushAll(context, prefs);
            Log.i(TAG, "saved Dock/steering/app-DPI/keyboard configuration published");
        } catch (RuntimeException e) {
            Log.e(TAG, "saved configuration publication failed", e);
        }
    }
}
