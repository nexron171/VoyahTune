package ru.big.town.anative;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Короткая синхронная защита родного дока на время запуска окна.
 *
 * <p>Launcher решает, разрешить ли {@code dismiss()}, по своему асинхронно обновляемому foreground-кэшу.
 * При запуске извне Launcher (кнопка руля, Native receiver) dismiss может прийти раньше обновления кэша.
 * Одной записью в Settings.Global публикуем display + пакет + deadline до startActivity; launcher hook
 * блокирует dismiss на этом дисплее, пока foreground-события догоняют запуск.</p>
 */
final class DockLaunchGuard {
    static final String KEY_PREFIX = "voyahtune_dockLaunchGuard";
    private static final long HOLD_MS = 5_000L;
    private static final String TAG = "voyahdock";

    private DockLaunchGuard() {}

    static void arm(Context context, int displayId, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return;
        if (displayId != 0 && displayId != 1) return;

        // Один putString вместо отдельных pkg/deadline: dismiss никогда не увидит половину новой записи.
        long deadline = SystemClock.elapsedRealtime() + HOLD_MS;
        String payload = deadline + "|" + packageName;
        try {
            Settings.Global.putString(context.getContentResolver(), KEY_PREFIX + displayId, payload);
            Log.i(TAG, "launch guard armed screen=" + displayId + " pkg=" + packageName
                    + " holdMs=" + HOLD_MS);
        } catch (Exception e) {
            // Guard — страховка. Ошибка записи не должна запрещать сам запуск приложения.
            Log.w(TAG, "launch guard arm failed: " + e.getMessage());
        }
    }
}
