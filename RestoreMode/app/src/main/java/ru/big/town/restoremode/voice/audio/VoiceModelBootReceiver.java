package ru.big.town.restoremode.voice.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ru.big.town.restoremode.VoiceWarmupService;

/**
 * Preloads the offline voice models right after a reboot so the first assistant invocation is as
 * fast as the subsequent ones. The warmup service itself checks whether the assistant is enabled
 * and no-ops otherwise, so this receiver is safe to keep registered at all times.
 */
public class VoiceModelBootReceiver extends BroadcastReceiver {
    private static final String TAG = "VoyahVoice";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICK_BOOT_COMPLETED".equals(action)) {
            return;
        }
        try {
            VoiceWarmupService.sync(context);
            Log.i(TAG, "boot: voice warmup requested (" + action + ")");
        } catch (RuntimeException e) {
            // A failed background start only degrades to the previous cold-first-invocation
            // behaviour; it must never crash the boot broadcast.
            Log.w(TAG, "boot: voice warmup could not be started", e);
        }
    }
}
