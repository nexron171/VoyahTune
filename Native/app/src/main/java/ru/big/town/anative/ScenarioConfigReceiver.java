package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ru.big.town.common.ScenarioProtocol;

/**
 * Защищённый (signature-permission) приём снимка сценариев из RestoreMode.
 *
 * <p>Снимок сохраняется в NativePrefs, чтобы определения пережили перезапуск сервиса, и сразу
 * передаётся работающему движку. Сценарии доступны и в Light, поэтому приёмник не ограничен Full.</p>
 */
public class ScenarioConfigReceiver extends BroadcastReceiver {
    private static final String TAG = "$$$ ScenarioConfig $$$";

    /** Последний сохранённый снимок; движок читает его при старте сервиса. */
    static String loadPersisted(Context context) {
        return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .getString(ScenarioProtocol.KEY_SCENARIOS, "[]");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ScenarioProtocol.ACTION_CONFIG.equals(intent.getAction())) return;
        String json = intent.getStringExtra(ScenarioProtocol.EXTRA_JSON);
        if (json == null) json = "[]";
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit().putString(ScenarioProtocol.KEY_SCENARIOS, json).apply();
        Log.i(TAG, "Снимок сценариев получен, символов: " + json.length());
        SetModesService.notifyScenarioConfigChanged(context);
    }
}
