package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ru.big.town.common.ScenarioProtocol;

/**
 * Определения пользовательских сценариев в {@code DrivePreferences} (ключ {@code scenarios}).
 *
 * <p>Сценарий = необязательные событийные триггеры, условия проверки и последовательность шагов
 * (действие или пауза). Плитка и голосовая команда не хранятся как триггеры: они доступны
 * включённому сценарию всегда, а {@link Scenario#showTile} только показывает плитку.</p>
 *
 * <p>Полный снимок публикуется в Native как JSON тем же форматом ({@link #toJson}).</p>
 */
final class ScenarioStore {
    private ScenarioStore() {}

    static final class Trigger {
        String type = ScenarioProtocol.TRIGGER_GEAR;
        String value = "";
        String edge = "";

        Trigger() {}

        Trigger(String type, String value, String edge) {
            this.type = type;
            this.value = value;
            this.edge = edge;
        }
    }

    static final class Condition {
        String type = ScenarioProtocol.CONDITION_LIGHT;
        String op = ScenarioProtocol.OP_LE;
        int value = 0;
        String mode = "";
        String from = "00:00";
        String to = "23:59";
        String days = "";
    }

    static final class Step {
        String type = ScenarioProtocol.STEP_ACTION;
        String value = "";
        int seconds = 5;
    }

    static final class Scenario {
        String id = UUID.randomUUID().toString();
        String name = "";
        boolean enabled = true;
        boolean showTile = false;
        final List<Trigger> triggers = new ArrayList<>();
        final List<Condition> conditions = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();

        /** Сценарию нужен датчик освещённости (триггер или условие). */
        boolean usesLight() {
            for (Trigger trigger : triggers) {
                if (ScenarioProtocol.TRIGGER_LIGHT.equals(trigger.type)) return true;
            }
            for (Condition condition : conditions) {
                if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) return true;
            }
            return false;
        }
    }

    /** Все сохранённые сценарии; повреждённые записи пропускаются. */
    static List<Scenario> load(SharedPreferences prefs) {
        return fromJson(prefs.getString(ScenarioProtocol.KEY_SCENARIOS, "[]"));
    }

    static void save(SharedPreferences prefs, List<Scenario> scenarios) {
        prefs.edit().putString(ScenarioProtocol.KEY_SCENARIOS, toJson(scenarios)).apply();
    }

    static Scenario find(SharedPreferences prefs, String id) {
        if (id == null) return null;
        for (Scenario scenario : load(prefs)) {
            if (id.equals(scenario.id)) return scenario;
        }
        return null;
    }

    /** Добавляет сценарий с уникальным именем и возвращает его (или {@code null} при лимите). */
    static Scenario create(SharedPreferences prefs, String name) {
        List<Scenario> scenarios = load(prefs);
        if (scenarios.size() >= ScenarioProtocol.MAX_SCENARIOS) return null;
        Scenario scenario = new Scenario();
        scenario.name = uniqueName(scenarios, name, scenario.id);
        scenarios.add(scenario);
        save(prefs, scenarios);
        return scenario;
    }

    static void remove(SharedPreferences prefs, String id) {
        List<Scenario> scenarios = load(prefs);
        scenarios.removeIf(scenario -> scenario.id.equals(id));
        save(prefs, scenarios);
    }

    /** Заменяет сценарий с тем же id нормализованной копией. */
    static void update(SharedPreferences prefs, Scenario changed) {
        if (changed == null) return;
        List<Scenario> scenarios = load(prefs);
        for (int i = 0; i < scenarios.size(); i++) {
            if (scenarios.get(i).id.equals(changed.id)) {
                scenarios.set(i, normalize(changed));
                save(prefs, scenarios);
                return;
            }
        }
    }

    /**
     * Проверка имени для редактора: {@code null} — можно использовать, иначе текст ошибки.
     * Имя не должно быть пустым, слишком длинным, повторяться и ломать разбор голосовой фразы.
     */
    static String validateName(List<Scenario> all, String name, String selfId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return "Введите название";
        if (trimmed.length() > ScenarioProtocol.MAX_NAME_LENGTH) {
            return "Не длиннее " + ScenarioProtocol.MAX_NAME_LENGTH + " символов";
        }
        if (!VoiceCommandCatalog.phraseUsable(trimmed)) {
            return "Название содержит слова, мешающие разбору команды («не», «или», «если», «затем»…)";
        }
        String key = trimmed.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (Scenario scenario : all) {
            if (selfId != null && selfId.equals(scenario.id)) continue;
            if (scenario.name.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').equals(key)) {
                return "Такое название уже есть";
            }
        }
        return null;
    }

    private static String uniqueName(List<Scenario> all, String base, String selfId) {
        String desired = base == null || base.trim().isEmpty() ? "Сценарий" : base.trim();
        if (validateName(all, desired, selfId) == null) return desired;
        for (int i = 2; i <= ScenarioProtocol.MAX_SCENARIOS + 1; i++) {
            String candidate = desired + " " + i;
            if (validateName(all, candidate, selfId) == null) return candidate;
        }
        return desired;
    }

    /** Сериализация полного списка для prefs и для публикации в Native. */
    static String toJson(List<Scenario> scenarios) {
        JSONArray array = new JSONArray();
        if (scenarios != null) {
            for (Scenario scenario : scenarios) {
                if (array.length() >= ScenarioProtocol.MAX_SCENARIOS) break;
                JSONObject object = toObject(normalize(scenario));
                if (object != null) array.put(object);
            }
        }
        return array.toString();
    }

    /** Разбор снимка. Повреждённые объекты и неизвестные типы триггеров/условий пропускаются. */
    static List<Scenario> fromJson(String json) {
        List<Scenario> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && result.size() < ScenarioProtocol.MAX_SCENARIOS; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Scenario scenario = fromObject(object);
                if (scenario != null) result.add(scenario);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static Scenario fromObject(JSONObject object) {
        String id = object.optString(ScenarioProtocol.KEY_ID, "");
        if (id.isEmpty()) return null;
        Scenario scenario = new Scenario();
        scenario.id = id;
        scenario.name = object.optString(ScenarioProtocol.KEY_NAME, "").trim();
        scenario.enabled = object.optBoolean(ScenarioProtocol.KEY_ENABLED, true);
        scenario.showTile = object.optBoolean(ScenarioProtocol.KEY_SHOW_TILE, false);
        JSONArray triggers = object.optJSONArray(ScenarioProtocol.KEY_TRIGGERS);
        if (triggers != null) {
            for (int i = 0; i < triggers.length(); i++) {
                JSONObject value = triggers.optJSONObject(i);
                if (value == null) continue;
                Trigger trigger = new Trigger(
                        value.optString(ScenarioProtocol.KEY_TYPE, ""),
                        value.optString(ScenarioProtocol.KEY_VALUE, ""),
                        value.optString(ScenarioProtocol.KEY_EDGE, ""));
                if (validTrigger(trigger)) scenario.triggers.add(trigger);
            }
        }
        JSONArray conditions = object.optJSONArray(ScenarioProtocol.KEY_CONDITIONS);
        if (conditions != null) {
            for (int i = 0; i < conditions.length(); i++) {
                JSONObject value = conditions.optJSONObject(i);
                if (value == null) continue;
                Condition condition = new Condition();
                condition.type = value.optString(ScenarioProtocol.KEY_TYPE, "");
                condition.op = value.optString(ScenarioProtocol.KEY_OP, ScenarioProtocol.OP_LE);
                condition.value = value.optInt(ScenarioProtocol.KEY_VALUE, 0);
                condition.mode = value.optString(ScenarioProtocol.KEY_MODE, "");
                condition.from = value.optString(ScenarioProtocol.KEY_FROM, "00:00");
                condition.to = value.optString(ScenarioProtocol.KEY_TO, "23:59");
                condition.days = value.optString(ScenarioProtocol.KEY_DAYS, "");
                if (validCondition(condition)) scenario.conditions.add(condition);
            }
        }
        JSONArray steps = object.optJSONArray(ScenarioProtocol.KEY_STEPS);
        if (steps != null) {
            for (int i = 0; i < steps.length() && scenario.steps.size() < ScenarioProtocol.MAX_STEPS; i++) {
                JSONObject value = steps.optJSONObject(i);
                if (value == null) continue;
                Step step = new Step();
                step.type = value.optString(ScenarioProtocol.KEY_TYPE, ScenarioProtocol.STEP_ACTION);
                step.value = value.optString(ScenarioProtocol.KEY_VALUE, "");
                step.seconds = value.optInt(ScenarioProtocol.KEY_SECONDS, 5);
                if (validStep(step)) scenario.steps.add(step);
            }
        }
        return scenario;
    }

    private static JSONObject toObject(Scenario scenario) {
        try {
            JSONObject object = new JSONObject();
            object.put(ScenarioProtocol.KEY_ID, scenario.id);
            object.put(ScenarioProtocol.KEY_NAME, scenario.name);
            object.put(ScenarioProtocol.KEY_ENABLED, scenario.enabled);
            object.put(ScenarioProtocol.KEY_SHOW_TILE, scenario.showTile);
            JSONArray triggers = new JSONArray();
            for (Trigger trigger : scenario.triggers) {
                JSONObject value = new JSONObject();
                value.put(ScenarioProtocol.KEY_TYPE, trigger.type);
                value.put(ScenarioProtocol.KEY_VALUE, trigger.value);
                if (!trigger.edge.isEmpty()) value.put(ScenarioProtocol.KEY_EDGE, trigger.edge);
                triggers.put(value);
            }
            object.put(ScenarioProtocol.KEY_TRIGGERS, triggers);
            JSONArray conditions = new JSONArray();
            for (Condition condition : scenario.conditions) {
                JSONObject value = new JSONObject();
                value.put(ScenarioProtocol.KEY_TYPE, condition.type);
                if (!condition.op.isEmpty()) value.put(ScenarioProtocol.KEY_OP, condition.op);
                value.put(ScenarioProtocol.KEY_VALUE, condition.value);
                if (!condition.mode.isEmpty()) value.put(ScenarioProtocol.KEY_MODE, condition.mode);
                if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
                    value.put(ScenarioProtocol.KEY_FROM, condition.from);
                    value.put(ScenarioProtocol.KEY_TO, condition.to);
                    value.put(ScenarioProtocol.KEY_DAYS, condition.days);
                }
                conditions.put(value);
            }
            object.put(ScenarioProtocol.KEY_CONDITIONS, conditions);
            JSONArray steps = new JSONArray();
            for (Step step : scenario.steps) {
                JSONObject value = new JSONObject();
                value.put(ScenarioProtocol.KEY_TYPE, step.type);
                if (ScenarioProtocol.STEP_PAUSE.equals(step.type)) {
                    value.put(ScenarioProtocol.KEY_SECONDS, ScenarioProtocol.clampPauseSeconds(step.seconds));
                } else {
                    value.put(ScenarioProtocol.KEY_VALUE, step.value);
                }
                steps.put(value);
            }
            object.put(ScenarioProtocol.KEY_STEPS, steps);
            return object;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Приводит сценарий к допустимому виду: обрезает имя, отбрасывает мусорные шаги и лимиты. */
    static Scenario normalize(Scenario scenario) {
        if (scenario == null) return null;
        if (scenario.id.isEmpty()) scenario.id = UUID.randomUUID().toString();
        scenario.name = scenario.name == null ? "" : scenario.name.trim();
        scenario.triggers.removeIf(trigger -> !validTrigger(trigger));
        scenario.conditions.removeIf(condition -> !validCondition(condition));
        scenario.steps.removeIf(step -> !validStep(step));
        while (scenario.steps.size() > ScenarioProtocol.MAX_STEPS) {
            scenario.steps.remove(scenario.steps.size() - 1);
        }
        for (Step step : scenario.steps) {
            if (ScenarioProtocol.STEP_PAUSE.equals(step.type)) {
                step.seconds = ScenarioProtocol.clampPauseSeconds(step.seconds);
            }
        }
        return scenario;
    }

    static boolean validTrigger(Trigger trigger) {
        if (trigger == null) return false;
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

    static boolean validCondition(Condition condition) {
        if (condition == null) return false;
        if (ScenarioProtocol.CONDITION_DRIVE_MODE.equals(condition.type)) {
            return ScenarioProtocol.validDriveMode(condition.mode);
        }
        if (ScenarioProtocol.CONDITION_TIME.equals(condition.type)) {
            return ScenarioProtocol.parseClock(condition.from) != null
                    && ScenarioProtocol.parseClock(condition.to) != null;
        }
        if (ScenarioProtocol.CONDITION_LIGHT.equals(condition.type)) {
            return validRange(condition, ScenarioProtocol.LIGHT_MIN, ScenarioProtocol.LIGHT_MAX);
        }
        if (ScenarioProtocol.CONDITION_TEMP_OUT.equals(condition.type)
                || ScenarioProtocol.CONDITION_SOC.equals(condition.type)) {
            return validRange(condition, -60, 100);
        }
        return false;
    }

    private static boolean validRange(Condition condition, int min, int max) {
        return ScenarioProtocol.validOp(condition.op)
                && condition.value >= min && condition.value <= max;
    }

    static boolean validStep(Step step) {
        if (step == null) return false;
        if (ScenarioProtocol.STEP_PAUSE.equals(step.type)) {
            return step.seconds >= ScenarioProtocol.MIN_PAUSE_SECONDS;
        }
        return ScenarioProtocol.STEP_ACTION.equals(step.type) && step.value != null && !step.value.isEmpty();
    }
}
