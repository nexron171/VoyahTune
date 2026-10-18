package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ru.big.town.common.InfrastructureProfile;
import ru.big.town.common.ScreenBrightnessSettings;

/** Receives a signature-protected, complete configuration snapshot. */
public final class ScreenBrightnessConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ScreenBrightnessSettings.ACTION_CONFIG.equals(intent.getAction())
                || InfrastructureProfile.read(context) != InfrastructureProfile.OD) return;
        String rules = intent.getStringExtra(ScreenBrightnessSettings.RULES);
        try {
            ScreenBrightnessSettings.decode(rules);
        } catch (IllegalArgumentException error) {
            Log.w("ScreenBrightness", "Rejected invalid configuration", error);
            return;
        }
        android.content.SharedPreferences preferences =
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
        if (preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false)
                && !intent.getBooleanExtra(ScreenBrightnessSettings.ENABLED, false)) {
            preferences
                    .edit()
                    .putBoolean(ScreenBrightnessModeController.RESTORE_PENDING, true)
                    .apply();
        }
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(
                        ScreenBrightnessSettings.ENABLED,
                        intent.getBooleanExtra(ScreenBrightnessSettings.ENABLED, false))
                .putString(ScreenBrightnessSettings.RULES, rules)
                .apply();
        Context application = context.getApplicationContext();
        // Restore can hold the observation lock while querying a remote settings provider.
        ApplyEngine.postIndependentUserCommand(
                "screen brightness settings",
                () -> AutoLightSettings.refreshObservation(application));
    }
}
