package ru.big.town.anative;

import ru.big.town.common.ScenarioProtocol;

/** Android-free решение: сработал ли триггер и выполнены ли условия сценария. */
final class ScenarioPolicy {
    /** Снимок состояния автомобиля для проверки условий. */
    static final class Snapshot {
        /** Нет данных. */
        static final int UNKNOWN = Integer.MIN_VALUE;

        final int lightLevel;   // 0..7 или -1
        final int ambientTemp;  // °C или UNKNOWN
        final int socPercent;   // 0..100 или -1
        final String driveMode; // ECO/COMFORT/SPORT/OUTING/INDIVIDUAL/SNOW или null
        final int minutesOfDay; // 0..1439
        final int dayOfWeek;    // 1 = понедельник … 7 = воскресенье

        Snapshot(int lightLevel, int ambientTemp, int socPercent, String driveMode,
                 int minutesOfDay, int dayOfWeek) {
            this.lightLevel = lightLevel;
            this.ambientTemp = ambientTemp;
            this.socPercent = socPercent;
            this.driveMode = driveMode;
            this.minutesOfDay = minutesOfDay;
            this.dayOfWeek = dayOfWeek;
        }
    }

    /** Состояние освещённости с гистерезисом как у автосвета; HOLD — в зоне неопределённости. */
    enum LightState {
        DARK,
        BRIGHT,
        HOLD
    }

    private ScenarioPolicy() {}

    static LightState lightState(int level) {
        if (level < 0) return LightState.HOLD;
        if (level <= LightThresholds.DEFAULT_ON) return LightState.DARK;
        if (level > LightThresholds.DEFAULT_OFF) return LightState.BRIGHT;
        return LightState.HOLD;
    }

    /** Триггер по положению селектора. */
    static boolean matchesGear(ScenarioDefinition scenario, int gearValue) {
        for (ScenarioDefinition.Trigger trigger : scenario.triggers) {
            if (!ScenarioProtocol.TRIGGER_GEAR.equals(trigger.type)) continue;
            Integer wanted = ScenarioProtocol.gearValue(trigger.value);
            if (wanted != null && wanted == gearValue) return true;
        }
        return false;
    }

    /** Триггер по двери; недостоверная маска (есть недоступное поле) не срабатывает. */
    static boolean matchesDoor(ScenarioDefinition scenario, int doorMask) {
        if ((doorMask & CanBusEvent.DOOR_UNKNOWN) != 0) return false;
        for (ScenarioDefinition.Trigger trigger : scenario.triggers) {
            if (!ScenarioProtocol.TRIGGER_DOOR.equals(trigger.type)) continue;
            int bit = DoorsStateController.bitFor(trigger.value);
            if (bit == 0) continue;
            boolean open = (doorMask & bit) != 0;
            if (ScenarioProtocol.EDGE_OPEN.equals(trigger.edge) && open) return true;
            if (ScenarioProtocol.EDGE_CLOSE.equals(trigger.edge) && !open) return true;
        }
        return false;
    }

    /** Триггер по освещённости: только на ребро перехода в тёмное или светлое состояние. */
    static boolean matchesLight(ScenarioDefinition scenario, LightState previous, LightState current) {
        if (current == LightState.HOLD || current == previous) return false;
        for (ScenarioDefinition.Trigger trigger : scenario.triggers) {
            if (!ScenarioProtocol.TRIGGER_LIGHT.equals(trigger.type)) continue;
            if (ScenarioProtocol.LIGHT_DARK.equals(trigger.value) && current == LightState.DARK) {
                return true;
            }
            if (ScenarioProtocol.LIGHT_BRIGHT.equals(trigger.value) && current == LightState.BRIGHT) {
                return true;
            }
        }
        return false;
    }

    /** Все условия должны быть выполнены; неизвестное значение условия считается невыполненным. */
    static boolean conditionsMet(ScenarioDefinition scenario, Snapshot snapshot) {
        return firstFailingCondition(scenario, snapshot) == null;
    }

    static boolean hasCondition(ScenarioDefinition scenario, String conditionType) {
        for (ScenarioDefinition.Condition condition : scenario.conditions) {
            if (conditionType.equals(condition.type)) return true;
        }
        return false;
    }

    static String firstFailingCondition(ScenarioDefinition scenario, Snapshot snapshot) {
        for (ScenarioDefinition.Condition condition : scenario.conditions) {
            if (!conditionMet(condition, snapshot)) return describeFailure(condition, snapshot);
        }
        return null;
    }

    private static String describeFailure(ScenarioDefinition.Condition condition, Snapshot snapshot) {
        if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) {
            return "освещённость (нужно " + symbol(condition.op) + " " + condition.value + ", "
                    + current(snapshot.lightLevel, "") + ")";
        }
        if (ScenarioProtocol.CONDITION_TEMP_OUT.equals(condition.type)) {
            return "уличная температура (нужно " + symbol(condition.op) + " " + condition.value + " °C, "
                    + (snapshot.ambientTemp == Snapshot.UNKNOWN
                    ? "данных нет" : "сейчас " + snapshot.ambientTemp + " °C") + ")";
        }
        if (ScenarioProtocol.CONDITION_SOC.equals(condition.type)) {
            return "заряд (нужно " + symbol(condition.op) + " " + condition.value + " %, "
                    + current(snapshot.socPercent, " %") + ")";
        }
        if (ScenarioProtocol.CONDITION_DRIVE_MODE.equals(condition.type)) {
            return "режим движения (нужно " + condition.mode + ", "
                    + (snapshot.driveMode == null ? "данных нет" : "сейчас " + snapshot.driveMode) + ")";
        }
        if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
            return "время (нужно " + condition.from + "–" + condition.to
                    + (condition.days == null || condition.days.trim().isEmpty()
                    ? ", ежедневно" : ", дни " + condition.days)
                    + ", сейчас " + clock(snapshot.minutesOfDay) + ")";
        }
        return condition.type;
    }

    private static String current(int value, String unit) {
        return value < 0 ? "данных нет" : "сейчас " + value + unit;
    }

    private static String symbol(String operator) {
        if (ScenarioProtocol.OP_LE.equals(operator)) return "≤";
        if (ScenarioProtocol.OP_GE.equals(operator)) return "≥";
        return "=";
    }

    private static String clock(int minutesOfDay) {
        if (minutesOfDay < 0) return "—";
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }

    private static boolean conditionMet(ScenarioDefinition.Condition condition, Snapshot snapshot) {
        if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) {
            return snapshot.lightLevel >= 0
                    && ScenarioProtocol.compare(snapshot.lightLevel, condition.op, condition.value);
        }
        if (ScenarioProtocol.CONDITION_TEMP_OUT.equals(condition.type)) {
            return snapshot.ambientTemp != Snapshot.UNKNOWN
                    && ScenarioProtocol.compare(snapshot.ambientTemp, condition.op, condition.value);
        }
        if (ScenarioProtocol.CONDITION_SOC.equals(condition.type)) {
            return snapshot.socPercent >= 0
                    && ScenarioProtocol.compare(snapshot.socPercent, condition.op, condition.value);
        }
        if (ScenarioProtocol.CONDITION_DRIVE_MODE.equals(condition.type)) {
            return condition.mode.equals(snapshot.driveMode);
        }
        if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
            return timeMatches(condition, snapshot);
        }
        // Неизвестное условие не пропускаем: сценарий не должен выполняться частично.
        return false;
    }

    /** Интервал поддерживает переход через полночь; дни недели — CSV 1..7, пусто — каждый день. */
    static boolean timeMatches(ScenarioDefinition.Condition condition, Snapshot snapshot) {
        Integer from = ScenarioProtocol.parseClock(condition.from);
        Integer to = ScenarioProtocol.parseClock(condition.to);
        if (from == null || to == null) return false;
        if (!daysMatch(condition.days, snapshot.dayOfWeek)) return false;
        int now = snapshot.minutesOfDay;
        if (from <= to) return now >= from && now <= to;
        return now >= from || now <= to;
    }

    static boolean daysMatch(String days, int dayOfWeek) {
        if (days == null || days.trim().isEmpty()) return true;
        for (String part : days.split(",")) {
            try {
                if (Integer.parseInt(part.trim()) == dayOfWeek) return true;
            } catch (NumberFormatException ignored) {
                // Мусор в списке дней игнорируем, остальные значения продолжают действовать.
            }
        }
        return false;
    }
}
