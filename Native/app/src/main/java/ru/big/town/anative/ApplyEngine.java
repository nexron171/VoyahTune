package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

/**
 * Централизованный «движок» применения настроек вождения (drive/energy/recycle/…).
 *
 * <p>Заменяет прежний {@code SetModesService.worker(...)} и решает проблему «после пробуждения
 * настройки не применяются, пока не нажмёшь Применить». Ключевые свойства:</p>
 *
 * <ul>
 *   <li><b>Один поток на все отправки.</b> Вся работа сериализована на выделенном
 *       HandlerThread — циклы применения, кнопка «Применить» и разовые команды
 *       (кнопка на руле) никогда не шлют в CAN одновременно.</li>
 *   <li><b>Дебаунс + коалесинг.</b> Пачка событий пробуждения (несколько power-состояний
 *       подряд, SCREEN_ON и т.п.) сворачивается в один цикл — {@link #scheduleApply(String)}.
 *       Триггер, пришедший во время уже идущего цикла, считается «покрытым» им и не
 *       порождает второй цикл (см. {@link #lastCycleEndUptime}).</li>
 *   <li><b>Ожидание готовности настроек.</b> Настройки читаются из ContentProvider соседнего
 *       приложения, которое на раннем пробуждении может быть ещё не поднято. Движок повторяет
 *       чтение с паузами: первые {@link #PROVIDER_ONLY_ATTEMPTS} попыток принимаются только
 *       свежие данные провайдера, дальше соглашаемся и на локальный кэш
 *       (см. {@link MainActivity#loadModes(Context, boolean)}).</li>
 *   <li><b>Многократная отправка.</b> Команды шлются несколько раз (устойчивость к тому, что
 *       автомобиль может сбросить режим в первые секунды после пробуждения).</li>
 * </ul>
 */
public final class ApplyEngine {
    static final String TAG = "$$$ ApplyEngine $$$";

    // Дебаунс пачки триггеров пробуждения.
    private static final long DEBOUNCE_MS = 800;
    // Ожидание готовности настроек: до READY_MAX_ATTEMPTS попыток с паузой READY_RETRY_MS.
    // Первые PROVIDER_ONLY_ATTEMPTS попыток кэш не принимаем — даём провайдеру шанс подняться,
    // чтобы не применить устаревший снимок, когда свежие данные вот-вот будут доступны.
    private static final int  READY_MAX_ATTEMPTS     = 8;
    private static final int  PROVIDER_ONLY_ATTEMPTS = 2;
    private static final long READY_RETRY_MS         = 2500;
    // Параметры wake-цикла — как у проверенного временем worker(7, 3500): 8 проходов с паузой
    // 3.5 с (авто может сбрасывать режим, пока его системы поднимаются после пробуждения).
    private static final int  WAKE_REPEAT = 8;
    private static final long WAKE_PAUSE  = 3500;

    private static Handler bg;
    // Токен для дедупликации отложенного дебаунс-раннабла.
    private static final Object DEBOUNCE_TOKEN = new Object();

    // uptime-момент завершения последнего цикла. Триггер, запланированный ДО этого момента,
    // уже «покрыт» циклом: настройки были прочитаны и применены ПОСЛЕ прихода триггера.
    // Без этого пачка wake-состояний, растянутая дольше дебаунса (WAIT_FOR_VHAL → … → ON),
    // давала бы второй полный цикл — и кастомные команды пользователя ушли бы дважды.
    private static volatile long lastCycleEndUptime = -1;

    private ApplyEngine() {}

    private static synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("ApplyEngine");
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    /**
     * Дебаунс-триггер применения (boot, power-состояния, SCREEN_ON…). Несколько вызовов подряд
     * сворачиваются в один цикл; триггеры, пришедшие во время идущего цикла, им же и покрыты.
     */
    public static void scheduleApply(String reason) {
        final long scheduledAt = SystemClock.uptimeMillis();
        Log.i(TAG, "scheduleApply: " + reason);
        Handler h = bg();
        h.removeCallbacksAndMessages(DEBOUNCE_TOKEN);
        h.postAtTime(() -> {
            if (scheduledAt <= lastCycleEndUptime) {
                Log.i(TAG, "apply skipped (covered by previous cycle): " + reason);
                return;
            }
            applyInternal(WAKE_REPEAT, WAKE_PAUSE, null);
        }, DEBOUNCE_TOKEN, scheduledAt + DEBOUNCE_MS);
    }

    /**
     * Немедленное применение (кнопка «Применить»). Выполняется всегда (без skip-логики —
     * пользователь попросил явно). {@code onDone} вызывается по завершении цикла — для
     * разблокировки кнопки на клиенте.
     */
    public static void applyNow(int repeat, long pause, Runnable onDone) {
        bg().post(() -> applyInternal(repeat, pause, onDone));
    }

    /**
     * Разовая отправка на том же последовательном потоке, что и циклы применения
     * (команды «звёздочки» на руле и т.п.) — чтобы не слать в CAN из двух потоков сразу.
     */
    public static void postExclusive(String reason, Runnable action) {
        Log.i(TAG, "postExclusive: " + reason);
        bg().post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.e(TAG, "postExclusive [" + reason + "] failed: " + t.getMessage(), t);
            }
        });
    }

    // Выполняется строго на bg-потоке — сериализация даёт single-flight без флагов/локов.
    private static void applyInternal(int repeat, long pause, Runnable onDone) {
        try {
            runCycle(repeat, pause);
        } catch (Throwable t) {
            Log.e(TAG, "runCycle failed: " + t.getMessage(), t);
        } finally {
            lastCycleEndUptime = SystemClock.uptimeMillis();
            if (onDone != null) onDone.run();
        }
    }

    private static void runCycle(int repeat, long pause) {
        Context ctx = GlobalVars.SAVE_CONTEXT;
        if (ctx == null) {
            Log.w(TAG, "runCycle: no context, skip");
            return;
        }

        // 1) Дожидаемся готовности настроек (2=провайдер, 1=кэш, 0=нет данных).
        int status = 0;
        for (int attempt = 1; attempt <= READY_MAX_ATTEMPTS; attempt++) {
            boolean allowCache = attempt > PROVIDER_ONLY_ATTEMPTS;
            status = MainActivity.loadModes(ctx, allowCache);
            if (status > 0) break;
            Log.w(TAG, "runCycle: settings not ready (" + attempt + "/" + READY_MAX_ATTEMPTS
                    + (allowCache ? ", cache empty too" : ", provider-only phase") + ")");
            if (attempt < READY_MAX_ATTEMPTS) sleep(READY_RETRY_MS);
        }
        if (status == 0) {
            Log.e(TAG, "runCycle: no settings (provider+cache empty) — nothing to apply");
            return;
        }

        // 2) Несколько проходов отправки — на случай, что авто сбросит режим после пробуждения.
        for (int i = 0; i < repeat; i++) {
            boolean ok = MainActivity.runCmds();
            if (!ok) Log.w(TAG, "runCycle: some CAN sends failed on pass " + (i + 1) + "/" + repeat);
            if (i < repeat - 1) sleep(pause);
        }

        // 3) Кастомные команды пользователя (unlock/wake и т.п.).
        for (int i = 0; i < MainActivity.customCommandCount; i++) {
            MainActivity.setCanValues(1, MainActivity.getCustomCommand(), "custom command (unlock/wake)");
            sleep(pause);
        }
        Log.i(TAG, "runCycle: done (source=" + (status == 2 ? "provider" : "cache") + ")");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
