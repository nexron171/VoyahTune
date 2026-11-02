package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ru.big.town.common.ScreenBrightnessSettings;

/** Receives a signature-protected, complete configuration snapshot. */
public final class ScreenBrightnessConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ScreenBrightnessSettings.MANUAL.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, ScreenBrightnessService.class)
                    .putExtra("manualPercent", intent.getIntExtra("percent", -1))
                    .putExtra("manualScreen", intent.getIntExtra("screen", -1))
                    .putExtra("manualSensor", LightSensorService.lastKnownLevel()));
            return;
        }
        if (!ScreenBrightnessSettings.ACTION_CONFIG.equals(intent.getAction())) return;
        String rules = intent.getStringExtra(ScreenBrightnessSettings.RULES);
        try {
            ScreenBrightnessSettings.decode(rules);
            int threshold = intent.getIntExtra(ScreenBrightnessSettings.NIGHT_THRESHOLD,
                    ScreenBrightnessSettings.nightThreshold(rules));
            if (threshold < 0 || threshold > 7) throw new IllegalArgumentException("Invalid night threshold");
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
                .putBoolean(ScreenBrightnessSettings.ADAPTIVE,
                        intent.getBooleanExtra(ScreenBrightnessSettings.ADAPTIVE, true))
                .putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD,
                        intent.getIntExtra(ScreenBrightnessSettings.NIGHT_THRESHOLD,
                                ScreenBrightnessSettings.nightThreshold(rules)))
                .apply();
        Context application = context.getApplicationContext();
        // Restore can hold the observation lock while querying a remote settings provider.
        ApplyEngine.postIndependentUserCommand(
                "screen brightness settings",
                () -> AutoLightSettings.refreshObservation(application));
    }
}
