package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;

/** Постоянный receiver результата resize: работает, даже когда MainActivity остановлена. */
public class SplitRatioSaveReceiver extends BroadcastReceiver {
    public static final String ACTION = "ru.big.town.restoremode.SPLIT_RATIO_SAVE";
    private static final String TAG = "$$$ SplitRatioSave $$$";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        float split = intent.getFloatExtra("split", 0f);
        if (split <= 0.05f || split >= 0.95f) return;

        String presetId = intent.getStringExtra("presetId");
        int fallbackIdx = intent.getIntExtra("presetIdx", -1);
        SharedPreferences prefs = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        List<SplitStore.Preset> all = SplitStore.load(prefs);
        int found = -1;
        if (presetId != null && !presetId.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                if (presetId.equals(all.get(i).id)) { found = i; break; }
            }
        }
        // Совместимость с Native, установленным до появления стабильных id.
        if (found < 0 && fallbackIdx >= 0 && fallbackIdx < all.size()) found = fallbackIdx;
        if (found < 0 || !all.get(found).resizable) return;

        all.get(found).split = split;
        SplitStore.save(prefs, all);
        SplitConfigSync.pushAll(context, prefs);
        Log.i(TAG, "пропорция пресета " + all.get(found).id + " сохранена: " + split);
    }
}
