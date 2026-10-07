package ru.big.town.anative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.big.town.common.ScenarioProtocol;

/**
 * Разобранное определение сценария из снимка RestoreMode ({@code ScenarioStore.toJson}).
 *
 * <p>Схема общая с {@code ru.big.town.restoremode.ScenarioStore}; здесь только чтение и
 * защитная проверка, чтобы повреждённый снимок не запускал неполный сценарий.</p>
 */
final class ScenarioDefinition {
    static final class Trigger {
        final String type;
        final String value;
        final String edge;

        Trigger(String type, String value, String edge) {
            this.type = type;
            this.value = value;
            this.edge = edge;
        }
    }

    static final class Condition {
        final String type;
        final String op;
        final int value;
        final String mode;
        final String from;
        final String to;
        final String days;

        Condition(String type, String op, int value, String mode,
                  String from, String to, String days) {
            this.type = type;
            this.op = op;
            this.value = value;
            this.mode = mode;
            this.from = from;
            this.to = to;
            this.days = days;
        }
    }

    static final class Step {
        final String type;
        final String value;
        final int seconds;

        Step(String type, String value, int seconds) {
            this.type = type;
            this.value = value;
            this.seconds = seconds;
        }

        boolean isPause() {
            return ScenarioProtocol.STEP_PAUSE.equals(type);
        }
    }

    final String id;
    final String name;
    final boolean enabled;
    final boolean showTile;
    final List<Trigger> triggers;
    final List<Condition> conditions;
    final List<Step> steps;

    /** Package-private: сборка определения возможна и из тестов, и из разбора снимка. */
    ScenarioDefinition(String id, String name, boolean enabled, boolean showTile,
                       List<Trigger> triggers, List<Condition> conditions,
                       List<Step> steps) {
        this.id = id;
        this.name = name;
        this.enabled = enabled;
        this.showTile = showTile;
        this.triggers = Collections.unmodifiableList(triggers);
        this.conditions = Collections.unmodifiableList(conditions);
        this.steps = Collections.unmodifiableList(steps);
    }

    /** Сценарию нужен уровень датчика освещённости (триггер или условие). */
    boolean usesLight() {
        for (Trigger trigger : triggers) {
            if (ScenarioProtocol.TRIGGER_LIGHT.equals(trigger.type)) return true;
        }
        for (Condition condition : conditions) {
            if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) return true;
        }
        return false;
    }

    /** Есть ли событийный триггер (плитка и голос работают и без него). */
    boolean hasEventTrigger() {
        return !triggers.isEmpty();
    }

    boolean hasSteps() {
        return !steps.isEmpty();
    }

    /** Только включённые сценарии с непустой последовательностью действий. */
    static List<ScenarioDefinition> parse(String json) {
        List<ScenarioDefinition> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && result.size() < ScenarioProtocol.MAX_SCENARIOS; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                ScenarioDefinition scenario = parseOne(object);
                if (scenario != null) result.add(scenario);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static ScenarioDefinition parseOne(JSONObject object) {
        String id = object.optString(ScenarioProtocol.KEY_ID, "");
        if (id.isEmpty()) return null;
        String name = object.optString(ScenarioProtocol.KEY_NAME, "").trim();
        boolean enabled = object.optBoolean(ScenarioProtocol.KEY_ENABLED, true);
        boolean showTile = object.optBoolean(ScenarioProtocol.KEY_SHOW_TILE, false);
        List<Trigger> triggers = new ArrayList<>();
        JSONArray rawTriggers = object.optJSONArray(ScenarioProtocol.KEY_TRIGGERS);
        if (rawTriggers != null) {
            for (int i = 0; i < rawTriggers.length(); i++) {
                JSONObject value = rawTriggers.optJSONObject(i);
                if (value == null) continue;
                Trigger trigger = new Trigger(
                        value.optString(ScenarioProtocol.KEY_TYPE, ""),
                        value.optString(ScenarioProtocol.KEY_VALUE, ""),
                        value.optString(ScenarioProtocol.KEY_EDGE, ""));
                if (validTrigger(trigger)) triggers.add(trigger);
            }
        }
        List<Condition> conditions = new ArrayList<>();
        JSONArray rawConditions = object.optJSONArray(ScenarioProtocol.KEY_CONDITIONS);
        if (rawConditions != null) {
            for (int i = 0; i < rawConditions.length(); i++) {
                JSONObject value = rawConditions.optJSONObject(i);
                if (value == null) continue;
                Condition condition = new Condition(
                        value.optString(ScenarioProtocol.KEY_TYPE, ""),
                        value.optString(ScenarioProtocol.KEY_OP, ""),
                        value.optInt(ScenarioProtocol.KEY_VALUE, 0),
                        value.optString(ScenarioProtocol.KEY_MODE, ""),
                        value.optString(ScenarioProtocol.KEY_FROM, "00:00"),
                        value.optString(ScenarioProtocol.KEY_TO, "23:59"),
                        value.optString(ScenarioProtocol.KEY_DAYS, ""));
                if (validCondition(condition)) conditions.add(condition);
            }
        }
        List<Step> steps = new ArrayList<>();
        JSONArray rawSteps = object.optJSONArray(ScenarioProtocol.KEY_STEPS);
        if (rawSteps != null) {
            for (int i = 0; i < rawSteps.length() && steps.size() < ScenarioProtocol.MAX_STEPS; i++) {
                JSONObject value = rawSteps.optJSONObject(i);
                if (value == null) continue;
                Step step = new Step(
                        value.optString(ScenarioProtocol.KEY_TYPE, ScenarioProtocol.STEP_ACTION),
                        value.optString(ScenarioProtocol.KEY_VALUE, ""),
                        value.optInt(ScenarioProtocol.KEY_SECONDS, 5));
                if (validStep(step)) steps.add(step);
            }
        }
        return new ScenarioDefinition(id, name, enabled, showTile, triggers, conditions, steps);
    }

    private static boolean validTrigger(Trigger trigger) {
        if (ScenarioProtocol.TRIGGER_GEAR.equals(trigger.type)) {
            return ScenarioProtocol.validGear(trigger.value);
        }
        if (ScenarioProtocol.TRIGGER_DOOR.equals(trigger.type)) {
            return ScenarioProtocol.validDoor(trigger.value) && ScenarioProtocol.validEdge(trigger.edge);
        }
        if (ScenarioProtocol.TRIGGER_LIGHT.equals(trigger.type)) {
            return ScenarioProtocol.validLightTrigger(trigger.value);
        }
        return false;
    }

    private static boolean validCondition(Condition condition) {
        if (ScenarioProtocol.CONDITION_DRIVE_MODE.equals(condition.type)) {
            return ScenarioProtocol.validDriveMode(condition.mode);
        }
        if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
            return ScenarioProtocol.parseClock(condition.from) != null
                    && ScenarioProtocol.parseClock(condition.to) != null;
        }
        if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) {
            return ScenarioProtocol.validOp(condition.op)
                    && condition.value >= ScenarioProtocol.LIGHT_MIN
                    && condition.value <= ScenarioProtocol.LIGHT_MAX;
        }
        if (ScenarioProtocol.CONDITION_TEMP_OUT.equals(condition.type)
                || ScenarioProtocol.CONDITION_SOC.equals(condition.type)) {
            return ScenarioProtocol.validOp(condition.op)
                    && condition.value >= -60 && condition.value <= 100;
        }
        return false;
    }

    private static boolean validStep(Step step) {
        if (step.isPause()) return step.seconds >= ScenarioProtocol.MIN_PAUSE_SECONDS;
        return ScenarioProtocol.STEP_ACTION.equals(step.type)
                && step.value != null && !step.value.isEmpty();
    }
}
