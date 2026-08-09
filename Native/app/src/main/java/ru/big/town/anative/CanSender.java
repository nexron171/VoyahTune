package ru.big.town.anative;

import android.util.Log;

import java.util.function.BooleanSupplier;

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

    private static final int CAN_FRAME_SIZE = 10;
    // libqg_hal uses one process-global CAN descriptor. Serialize all current Java callers;
    // the native layer has the same guard for callers that bypass CanSender.
    private static final Object NATIVE_SEND_LOCK = new Object();
    // ApplyEngine installs a guard on its worker threads. It is checked immediately before every
    // native frame so sleep/reset can stop a multi-frame batch between two ioctl transactions.
    private static final ThreadLocal<BooleanSupplier> SEND_GUARD = new ThreadLocal<>();
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
     *         {@code false}, если нативный слой вернул ошибку (напр. не загрузилась libqg_hal)
     *         или фрейм некорректен. Иначе restore мог бы считаться успешным без CAN.
     */
    public static boolean send(int cmdNum, byte[] frame, String label) {
        if (frame == null || frame.length != CAN_FRAME_SIZE) {
            Log.w(TAG, "Invalid CAN frame suppressed [" + label + "]: length="
                    + (frame == null ? "null" : frame.length));
            return false;
        }
        if (!sendAllowed()) return false;
        if (debugMode) {
            Log.i(TAG, "EMULATE CAN [" + (label == null || label.isEmpty() ? "?" : label) + "]"
                    + " cmd=" + cmdNum + " frame=" + MainActivity.printHexBinary(frame));
            return true;
        }
        final int res;
        synchronized (NATIVE_SEND_LOCK) {
            // A batch may have waited for another caller's transaction while the car went to sleep.
            if (!sendAllowed()) return false;
            res = MainActivity.cis_can_control_bytes(cmdNum, frame);
        }
        if (res != 0) {
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
        // Режим движения состоит из 2–3 связанных кадров. Не даём свету/дворникам вклиниться
        // между ними: Java monitor reentrant, поэтому внутренний send(byte[]) безопасно возьмёт его снова.
        synchronized (NATIVE_SEND_LOCK) {
            for (byte[] frame : frames) {
                if (!sendAllowed()) return false;
                if (!send(cmdNum, frame, label)) return false;
            }
            return true;
        }
    }

    /** Executes a boolean CAN operation with a per-frame cooperative cancellation guard. */
    static boolean runGuardedSend(BooleanSupplier guard, BooleanSupplier operation) {
        BooleanSupplier previous = SEND_GUARD.get();
        SEND_GUARD.set(combine(previous, guard));
        try {
            return sendAllowed() && operation.getAsBoolean();
        } finally {
            restoreGuard(previous);
        }
    }

    /** Executes an action whose nested CanSender calls must observe the supplied guard. */
    static void runGuardedAction(BooleanSupplier guard, Runnable action) {
        BooleanSupplier previous = SEND_GUARD.get();
        SEND_GUARD.set(combine(previous, guard));
        try {
            if (sendAllowed()) action.run();
        } finally {
            restoreGuard(previous);
        }
    }

    private static BooleanSupplier combine(BooleanSupplier first, BooleanSupplier second) {
        if (first == null) return second;
        if (second == null) return first;
        return () -> first.getAsBoolean() && second.getAsBoolean();
    }

    private static void restoreGuard(BooleanSupplier previous) {
        if (previous == null) SEND_GUARD.remove();
        else SEND_GUARD.set(previous);
    }

    private static boolean sendAllowed() {
        BooleanSupplier guard = SEND_GUARD.get();
        if (guard == null) return true;
        try {
            return guard.getAsBoolean();
        } catch (Throwable t) {
            Log.e(TAG, "CAN guard failed; frame suppressed", t);
            return false;
        }
    }

    // Совместимость: вызовы без метки
    public static boolean send(int cmdNum, byte[] frame)   { return send(cmdNum, frame, null); }
    public static boolean send(int cmdNum, byte[][] frames) { return send(cmdNum, frames, null); }
}
