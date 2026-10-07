package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ru.big.town.common.ScenarioProtocol;

public class ScenarioStoreTest {

    private static ScenarioStore.Scenario scenario(String id, String name) {
        ScenarioStore.Scenario scenario = new ScenarioStore.Scenario();
        scenario.id = id;
        scenario.name = name;
        return scenario;
    }

    @Test
    public void gearTriggerRequiresKnownGear() {
        assertTrue(ScenarioStore.validTrigger(
                new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_GEAR, ScenarioProtocol.GEAR_D, "")));
        assertFalse(ScenarioStore.validTrigger(
                new ScenarioStore.Trigger(ScenarioProtocol.TRIGGER_GEAR, "X", "")));
    }

    @Test
    public void doorTriggerRequiresKnownDoorAndEdge() {
        assertTrue(ScenarioStore.validTrigger(new ScenarioStore.Trigger(
                ScenarioProtocol.TRIGGER_DOOR, ScenarioProtocol.DOOR_DRIVER, ScenarioProtocol.EDGE_OPEN)));
        assertFalse("нет ребра", ScenarioStore.validTrigger(new ScenarioStore.Trigger(
                ScenarioProtocol.TRIGGER_DOOR, ScenarioProtocol.DOOR_DRIVER, "")));
        assertFalse("неизвестная дверь", ScenarioStore.validTrigger(new ScenarioStore.Trigger(
                ScenarioProtocol.TRIGGER_DOOR, "sunroof", ScenarioProtocol.EDGE_CLOSE)));
    }

    @Test
    public void lightConditionStaysWithinSensorRange() {
        ScenarioStore.Condition condition = new ScenarioStore.Condition();
        condition.type = ScenarioProtocol.CONDITION_LIGHT;
        condition.op = ScenarioProtocol.OP_LE;
        condition.value = 3;
        assertTrue(ScenarioStore.validCondition(condition));
        condition.value = 9;
        assertFalse(ScenarioStore.validCondition(condition));
        condition.value = 3;
        condition.op = "approximately";
        assertFalse(ScenarioStore.validCondition(condition));
    }

    @Test
    public void timeConditionNeedsParseableClock() {
        ScenarioStore.Condition condition = new ScenarioStore.Condition();
        condition.type = ScenarioProtocol.CONDITION_TIME;
        condition.from = "07:00";
        condition.to = "23:30";
        assertTrue(ScenarioStore.validCondition(condition));
        condition.to = "25:00";
        assertFalse(ScenarioStore.validCondition(condition));
    }

    @Test
    public void driveModeConditionRejectsUnknownMode() {
        ScenarioStore.Condition condition = new ScenarioStore.Condition();
        condition.type = ScenarioProtocol.CONDITION_DRIVE_MODE;
        condition.mode = "SPORT";
        assertTrue(ScenarioStore.validCondition(condition));
        condition.mode = "RACE";
        assertFalse(ScenarioStore.validCondition(condition));
    }

    @Test
    public void stepsKeepActionsAndClampPause() {
        ScenarioStore.Scenario scenario = scenario("s1", "Утро");
        ScenarioStore.Step action = new ScenarioStore.Step();
        action.type = ScenarioProtocol.STEP_ACTION;
        action.value = "drive:SPORT";
        scenario.steps.add(action);

        ScenarioStore.Step pause = new ScenarioStore.Step();
        pause.type = ScenarioProtocol.STEP_PAUSE;
        pause.seconds = 9999;
        scenario.steps.add(pause);

        ScenarioStore.Step broken = new ScenarioStore.Step();
        broken.type = ScenarioProtocol.STEP_ACTION;
        broken.value = "";
        scenario.steps.add(broken);

        ScenarioStore.normalize(scenario);

        assertEquals(2, scenario.steps.size());
        assertEquals(ScenarioProtocol.MAX_PAUSE_SECONDS, scenario.steps.get(1).seconds);
    }

    @Test
    public void normalizeDropsUnknownTriggers() {
        ScenarioStore.Scenario scenario = scenario("s1", "Утро");
        scenario.triggers.add(new ScenarioStore.Trigger(
                ScenarioProtocol.TRIGGER_GEAR, ScenarioProtocol.GEAR_D, ""));
        scenario.triggers.add(new ScenarioStore.Trigger("speed", "120", ""));
        ScenarioStore.normalize(scenario);
        assertEquals(1, scenario.triggers.size());
    }

    @Test
    public void nameValidationRejectsEmptyLongReservedAndDuplicates() {
        List<ScenarioStore.Scenario> all = new ArrayList<>();
        all.add(scenario("a", "Утро"));
        all.add(scenario("b", "Ночь"));

        assertNotNull(ScenarioStore.validateName(all, "   ", null));
        assertNotNull(ScenarioStore.validateName(all, "А".repeat(ScenarioProtocol.MAX_NAME_LENGTH + 1), null));
        assertNotNull("отрицание ломает фразу", ScenarioStore.validateName(all, "не спорт", null));
        assertNotNull("разделитель ломает фразу", ScenarioStore.validateName(all, "день или ночь", null));
        assertNotNull("дубликат", ScenarioStore.validateName(all, "утро", null));
        assertNull("сам себя не считает дубликатом", ScenarioStore.validateName(all, "Утро", "a"));
        assertNull(ScenarioStore.validateName(all, "Вечер", null));
    }
}
