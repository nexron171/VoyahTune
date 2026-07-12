package ru.big.town.anative;

import android.util.Log;

/**
 * Централизованный слой отправки CAN-команд.
 *
 * <p>Если включён «Режим отладки» ({@link #setDebugMode(boolean)}), команды НЕ уходят в шину,
 * а логируются (эмуляция) — чтобы можно было отлаживать работу приложения без живого
 * автомобиля. В обычном режиме фреймы отправляются через нативный JNI-вызов.</p>
 *
 * <p>Значение флага отладки приходит из настроек RestoreMode (SharedPreferences
 * {@code debugMode}) через ContentProvider и обновляется в
 * {@link MainActivity#initValueModes(android.content.Context)}.</p>
 */
public final class CanSender {
    public static final String TAG = "$$$ CanSender $$$";

    private static volatile boolean debugMode = false;

    private CanSender() {}

    /** Установить режим отладки. true — эмуляция (лог вместо шины). */
    public static void setDebugMode(boolean enabled) {
        if (debugMode != enabled) {
            Log.i(TAG, "debugMode -> " + enabled + (enabled ? " (эмуляция CAN в логи)" : " (реальная отправка)"));
        }
        debugMode = enabled;
    }

    public static boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Отправка одного 10-байтного фрейма с человекочитаемой меткой (что это за команда).
     *
     * @return {@code true}, если фрейм отправлен (или сэмулирован в debug-режиме);
     *         {@code false}, если нативный слой вернул ошибку (напр. не загрузилась libqg_hal).
     *         Пустой/некорректный фрейм считаем «нечего слать» → {@code true}.
     */
    public static boolean send(int cmdNum, byte[] frame, String label) {
        if (frame == null || frame.length != 10) return true;
        if (debugMode) {
            Log.i(TAG, "EMULATE CAN [" + (label == null || label.isEmpty() ? "?" : label) + "]"
                    + " cmd=" + cmdNum + " frame=" + MainActivity.printHexBinary(frame));
            return true;
        }
        int res = MainActivity.cis_can_control_bytes(cmdNum, frame);
        if (res < 0) {
            Log.w(TAG, "CAN send failed (res=" + res + ") [" + label + "]");
            return false;
        }
        return true;
    }

    /**
     * Отправка набора фреймов с меткой.
     * @return {@code true}, только если ВСЕ фреймы ушли без ошибки.
     */
    public static boolean send(int cmdNum, byte[][] frames, String label) {
        if (frames == null) return true;
        boolean ok = true;
        for (byte[] frame : frames) {
            ok &= send(cmdNum, frame, label);
        }
        return ok;
    }

    // Совместимость: вызовы без метки
    public static boolean send(int cmdNum, byte[] frame)   { return send(cmdNum, frame, null); }
    public static boolean send(int cmdNum, byte[][] frames) { return send(cmdNum, frames, null); }
}
