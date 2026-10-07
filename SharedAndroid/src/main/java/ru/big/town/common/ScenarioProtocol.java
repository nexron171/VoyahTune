package ru.big.town.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Signature-protected RestoreMode/Native contract for пользовательских сценариев («Сценарии»).
 *
 * <p>Pure Java: компилируется в оба APK. Здесь только константы схемы и небольшие чистые
 * помощники (нормализация паузы, разбор значений триггеров), чтобы UI-редактор в RestoreMode и
 * движок в Native трактовали одну и ту же схему одинаково.</p>
 *
 * <p>Определения сценариев публикуются из RestoreMode полным снимком (JSON) и хранятся в Native.
 * Запуск одного сценария вручную идёт через Messenger ({@link #MSG_RUN}).</p>
 */
public final class ScenarioProtocol {
    private ScenarioProtocol() {}

    /** RestoreMode → Native: полный снимок определений (JSON-массив объектов сценариев). */
    public static final String ACTION_CONFIG = "ru.big.town.anative.SCENARIO_CONFIG";
    public static final String EXTRA_JSON = "scenariosJson";

    /** RestoreMode → Native через Messenger: выполнить один сценарий сейчас (data: {@link #EXTRA_ID}). */
    public static final int MSG_RUN = 94;
    public static final String EXTRA_ID = "id";
    /** С {@link #EXTRA_TEST} проверяются условия и снимается антидребезг; сценарий может быть выключен. */
    public static final String EXTRA_TEST = "test";

    /** Префикс голосового действия «запустить сценарий»: {@code scenario:<id>}. */
    public static final String ACTION_RUN_PREFIX = "scenario:";

    /** Ключ списка сценариев в DrivePreferences (RestoreMode) и NativePrefs (Native). */
    public static final String KEY_SCENARIOS = "scenarios";

    // --- Ключи объектов схемы ---
    public static final String KEY_ID = "id";
    public static final String KEY_NAME = "name";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_TILE = "showTile";
    public static final String KEY_TRIGGERS = "triggers";
    public static final String KEY_CONDITIONS = "conditions";
    public static final String KEY_STEPS = "steps";
    public static final String KEY_TYPE = "type";
    public static final String KEY_VALUE = "value";

    // --- Триггеры ---
    public static final String TRIGGER_GEAR = "gear";
    public static final String TRIGGER_DOOR = "door";
    public static final String TRIGGER_LIGHT = "light";

    /** Ребро срабатывания для триггера двери. */
    public static final String KEY_EDGE = "edge";
    public static final String EDGE_OPEN = "open";
    public static final String EDGE_CLOSE = "close";

    /** Значения триггера освещённости. */
    public static final String LIGHT_DARK = "dark";
    public static final String LIGHT_BRIGHT = "bright";

    /** Положения селектора. */
    public static final String GEAR_P = "P";
    public static final String GEAR_R = "R";
    public static final String GEAR_N = "N";
    public static final String GEAR_D = "D";

    /** Значения stable-enum gear из CanBus: Parking=0, Reverse=1, Neutral=2, Drive=3. */
    public static final int GEAR_VALUE_P = 0;
    public static final int GEAR_VALUE_R = 1;
    public static final int GEAR_VALUE_N = 2;
    public static final int GEAR_VALUE_D = 3;

    /** Идентификаторы дверей. */
    public static final String DOOR_DRIVER = "driver";
    public static final String DOOR_PASSENGER = "passenger";
    public static final String DOOR_REAR_LEFT = "rear_left";
    public static final String DOOR_REAR_RIGHT = "rear_right";
    public static final String DOOR_BOOT = "boot";
    public static final String DOOR_HOOD = "hood";

    public static final List<String> GEARS =
            Collections.unmodifiableList(Arrays.asList(GEAR_P, GEAR_R, GEAR_N, GEAR_D));
    public static final List<String> DOORS = Collections.unmodifiableList(Arrays.asList(
            DOOR_DRIVER, DOOR_PASSENGER, DOOR_REAR_LEFT, DOOR_REAR_RIGHT, DOOR_BOOT, DOOR_HOOD));
    public static final List<String> LIGHT_TRIGGERS =
            Collections.unmodifiableList(Arrays.asList(LIGHT_DARK, LIGHT_BRIGHT));

    // --- Условия ---
    public static final String CONDITION_LIGHT = "light";
    public static final String CONDITION_TEMP_OUT = "tempOut";
    public static final String CONDITION_SOC = "soc";
    public static final String CONDITION_DRIVE_MODE = "driveMode";
    public static final String CONDITION_TIME = "time";

    public static final String KEY_OP = "op";
    public static final String KEY_MODE = "mode";
    public static final String KEY_FROM = "from";
    public static final String KEY_TO = "to";
    public static final String KEY_DAYS = "days";

    /** Операторы сравнения: ≤, ≥, =. Буквенные, чтобы значение безопасно переживало JSON/prefs. */
    public static final String OP_LE = "le";
    public static final String OP_GE = "ge";
    public static final String OP_EQ = "eq";

    public static final List<String> OPS =
            Collections.unmodifiableList(Arrays.asList(OP_LE, OP_GE, OP_EQ));
    /** Режимы движения, которыми можно ограничить сценарий. */
    public static final List<String> DRIVE_MODES = Collections.unmodifiableList(Arrays.asList(
            "ECO", "COMFORT", "SPORT", "OUTING", "INDIVIDUAL", "SNOW"));

    // --- Шаги ---
    public static final String STEP_ACTION = "action";
    public static final String STEP_PAUSE = "pause";
    public static final String KEY_SECONDS = "seconds";

    // --- Ограничения ---
    public static final int MAX_SCENARIOS = 20;
    public static final int MAX_STEPS = 20;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MIN_PAUSE_SECONDS = 1;
    public static final int MAX_PAUSE_SECONDS = 600;

    /** Порог датчика освещённости: диапазон значений CarSignalService. */
    public static final int LIGHT_MIN = 0;
    public static final int LIGHT_MAX = 7;

    /** Пауза в допустимых границах, в секундах. */
    public static int clampPauseSeconds(int seconds) {
        if (seconds < MIN_PAUSE_SECONDS) return MIN_PAUSE_SECONDS;
        return Math.min(seconds, MAX_PAUSE_SECONDS);
    }

    public static boolean validGear(String gear) {
        return GEARS.contains(gear);
    }

    public static boolean validDoor(String door) {
        return DOORS.contains(door);
    }

    public static boolean validLightTrigger(String value) {
        return LIGHT_TRIGGERS.contains(value);
    }

    public static boolean validEdge(String edge) {
        return EDGE_OPEN.equals(edge) || EDGE_CLOSE.equals(edge);
    }

    public static boolean validOp(String op) {
        return OPS.contains(op);
    }

    public static boolean validDriveMode(String mode) {
        return DRIVE_MODES.contains(mode);
    }

    /** Значение stable-enum CanBus для положения селектора, или {@code null} для неизвестного. */
    public static Integer gearValue(String gear) {
        if (GEAR_P.equals(gear)) return GEAR_VALUE_P;
        if (GEAR_R.equals(gear)) return GEAR_VALUE_R;
        if (GEAR_N.equals(gear)) return GEAR_VALUE_N;
        if (GEAR_D.equals(gear)) return GEAR_VALUE_D;
        return null;
    }

    /** Сравнение значения с порогом по буквенному оператору. Неизвестный оператор → {@code false}. */
    public static boolean compare(int actual, String op, int threshold) {
        if (OP_LE.equals(op)) return actual <= threshold;
        if (OP_GE.equals(op)) return actual >= threshold;
        if (OP_EQ.equals(op)) return actual == threshold;
        return false;
    }

    /** «07:30» → минуты от полуночи, или {@code null} для некорректной строки. */
    public static Integer parseClock(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split(":");
        if (parts.length != 2) return null;
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null;
            return hours * 60 + minutes;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
