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

    /** Отправка одного 10-байтного фрейма с человекочитаемой меткой (что это за команда). */
    public static void send(int cmdNum, byte[] frame, String label) {
        if (frame == null || frame.length != 10) return;
        if (debugMode) {
            Log.i(TAG, "EMULATE CAN [" + (label == null || label.isEmpty() ? "?" : label) + "]"
                    + " cmd=" + cmdNum + " frame=" + MainActivity.printHexBinary(frame));
        } else {
            MainActivity.cis_can_control_bytes(cmdNum, frame);
        }
    }

    /** Отправка набора фреймов с меткой. */
    public static void send(int cmdNum, byte[][] frames, String label) {
        if (frames == null) return;
        for (byte[] frame : frames) {
            send(cmdNum, frame, label);
        }
    }

    // Совместимость: вызовы без метки
    public static void send(int cmdNum, byte[] frame)   { send(cmdNum, frame, null); }
    public static void send(int cmdNum, byte[][] frames) { send(cmdNum, frames, null); }
}
