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
    // Параметры wake-цикла: НУЖНО набрать WAKE_REPEAT УСПЕШНЫХ проходов (авто может сбрасывать режим,
    // пока его системы поднимаются после пробуждения) с паузой WAKE_PAUSE между отправками.
    // 3 успешных прохода с паузой 5 с: отправили → 5 с → отправили → 5 с → отправили.
    private static final int  WAKE_REPEAT = 3;
    private static final long WAKE_PAUSE  = 5000;
    // На пробуждении CAN-сервис/HAL поднимается не сразу — первые проходы падают (res=-1). Ждём готовности
    // CAN до этого дедлайна (первого успешного прохода), иначе фикс. окно заканчивалось ДО готовности CAN
    // и режим не применялся («нестабильно»).
    private static final long WAKE_CAN_DEADLINE_MS = 120_000;

    private static Handler bg;
    // Токен для дедупликации отложенного дебаунс-раннабла.
    private static final Object DEBOUNCE_TOKEN = new Object();

    // uptime-момент завершения последнего цикла. Триггер, запланированный ДО этого момента,
    // уже «покрыт» циклом: настройки были прочитаны и применены ПОСЛЕ прихода триггера.
    // Без этого пачка wake-состояний, растянутая дольше дебаунса (WAIT_FOR_VHAL → … → ON),
    // давала бы второй полный цикл — и кастомные команды пользователя ушли бы дважды.
    private static volatile long lastCycleEndUptime = -1;

    // req3-гейт внешнего синка режима. Внешний синк (TripStatsService.maybeSyncMode) должен ИГНОРИРОВАТЬ
    // «эхо» текущего режима машины, пока в этом цикле пробуждения не восстановлен СОХРАНЁННЫЙ режим.
    // На старте авто рапортует свой дефолт (ЭКО/Электро) ДО применения сохранённого режима — без гейта
    // этот дефолт затирал источник истины (провайдер RestoreMode + кэш), и восстановление применяло
    // именно его («всегда ЭКО/Электро на пробуждении»). Снимаем гейт после первого УСПЕШНОГО прохода
    // restore; сбрасываем при каждом новом цикле применения (scheduleApply) и на засыпании.
    private static volatile boolean restoreDoneThisCycle = false;

    /** true → в этом цикле пробуждения сохранённый режим уже восстановлён (можно принимать внешние смены режима). */
    public static boolean isRestoreDoneThisCycle() { return restoreDoneThisCycle; }

    /** Новый цикл пробуждения/засыпания: до следующего успешного restore внешний синк режима заглушён. */
    public static void resetRestoreGate() { restoreDoneThisCycle = false; }

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
        // Новый цикл применения → заглушаем внешний синк режима, пока restore не восстановит сохранённый
        // режим (иначе дефолт-эхо машины на пробуждении перезапишет источник истины). Синхронно и ДО
        // дебаунса: на пробуждении WAIT_FOR_VHAL приходит раньше готовности VHAL → раньше любого VehicleState.
        restoreDoneThisCycle = false;
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

        // 2) Отправляем ПОКА не наберём `repeat` УСПЕШНЫХ проходов (CAN готов) ИЛИ не выйдет дедлайн.
        //    Раньше было фикс. `repeat` проходов независимо от результата: на пробуждении CAN-сервис/HAL
        //    ещё поднимался, все проходы падали (res=-1) в отведённые ~28 с → режим не применялся. Теперь
        //    неуспешные проходы (CAN не готов) НЕ засчитываются и мы ждём его готовности до дедлайна, а
        //    после первого успеха добиваем `repeat` успешных для устойчивости к сбросу режима авто.
        long deadline = SystemClock.uptimeMillis() + WAKE_CAN_DEADLINE_MS;
        int okPasses = 0, tries = 0;
        while (true) {
            boolean ok = MainActivity.runCmds();
            tries++;
            if (ok) {
                okPasses++;
            } else {
                Log.w(TAG, "runCycle: CAN не готов, проход " + tries + " не прошёл (успешных=" + okPasses + ")");
            }
            if (okPasses >= repeat) break;                             // набрали нужное число успешных
            if (SystemClock.uptimeMillis() >= deadline) break;         // CAN так и не поднялся вовремя
            sleep(pause);
        }
        if (okPasses == 0) {
            Log.e(TAG, "runCycle: CAN не поднялся за " + (WAKE_CAN_DEADLINE_MS / 1000) + "с — режим НЕ применён");
        } else if (okPasses < repeat) {
            Log.w(TAG, "runCycle: применено частично — успешных проходов " + okPasses + "/" + repeat + " (tries=" + tries + ")");
        } else {
            Log.i(TAG, "runCycle: режим применён, успешных проходов " + okPasses + "/" + repeat + " (tries=" + tries + ")");
        }
        // Сохранённый режим восстановлен (хотя бы один успешный проход) → снимаем гейт: теперь внешние
        // смены режима (штатное меню машины) синкаются в источник истины. До этого — «эхо» дефолта машины
        // на старте игнорируется (см. restoreDoneThisCycle и TripStatsService.maybeSyncMode).
        if (okPasses > 0) restoreDoneThisCycle = true;

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
