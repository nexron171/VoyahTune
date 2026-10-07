package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.big.town.common.ScenarioProtocol;

public class ScenarioPolicyTest {

    private static ScenarioDefinition scenario(List<ScenarioDefinition.Trigger> triggers,
                                               List<ScenarioDefinition.Condition> conditions) {
        return new ScenarioDefinition("id", "Тест", true, false, triggers, conditions,
                Collections.singletonList(new ScenarioDefinition.Step(
                        ScenarioProtocol.STEP_ACTION, "drive:SPORT", 0)));
    }

    private static ScenarioPolicy.Snapshot snapshot(int light, int temp, int soc,
                                                    String driveMode, int minutes, int day) {
        return new ScenarioPolicy.Snapshot(light, temp, soc, driveMode, minutes, day);
    }

    @Test
    public void gearTriggerMatchesRequestedPosition() {
        ScenarioDefinition scenario = scenario(Collections.singletonList(
                new ScenarioDefinition.Trigger(ScenarioProtocol.TRIGGER_GEAR,
                        ScenarioProtocol.GEAR_D, "")), new ArrayList<>());
        assertTrue(ScenarioPolicy.matchesGear(scenario, ScenarioProtocol.GEAR_VALUE_D));
        assertFalse(ScenarioPolicy.matchesGear(scenario, ScenarioProtocol.GEAR_VALUE_P));
    }

    @Test
    public void doorTriggerHonoursEdgeAndRejectsUnknownMask() {
        ScenarioDefinition open = scenario(Collections.singletonList(
                new ScenarioDefinition.Trigger(ScenarioProtocol.TRIGGER_DOOR,
                        ScenarioProtocol.DOOR_DRIVER, ScenarioProtocol.EDGE_OPEN)), new ArrayList<>());
        assertTrue(ScenarioPolicy.matchesDoor(open,
                CanBusEvent.DOOR_DRIVER | CanBusEvent.DOOR_PASSENGER));
        assertFalse(ScenarioPolicy.matchesDoor(open, CanBusEvent.DOOR_PASSENGER));
        assertFalse("недостоверная маска не срабатывает", ScenarioPolicy.matchesDoor(open,
                CanBusEvent.DOOR_DRIVER | CanBusEvent.DOOR_UNKNOWN));

        ScenarioDefinition close = scenario(Collections.singletonList(
                new ScenarioDefinition.Trigger(ScenarioProtocol.TRIGGER_DOOR,
                        ScenarioProtocol.DOOR_BOOT, ScenarioProtocol.EDGE_CLOSE)), new ArrayList<>());
        assertTrue(ScenarioPolicy.matchesDoor(close, 0));
        assertFalse(ScenarioPolicy.matchesDoor(close, CanBusEvent.DOOR_BOOT));
    }

    @Test
    public void lightTriggerFiresOnEdgeOnly() {
        ScenarioDefinition scenario = scenario(Collections.singletonList(
                new ScenarioDefinition.Trigger(ScenarioProtocol.TRIGGER_LIGHT,
                        ScenarioProtocol.LIGHT_DARK, "")), new ArrayList<>());
        assertFalse("в зоне неопределённости не срабатывает",
                ScenarioPolicy.matchesLight(scenario, ScenarioPolicy.LightState.BRIGHT,
                        ScenarioPolicy.LightState.HOLD));
        assertTrue(ScenarioPolicy.matchesLight(scenario, ScenarioPolicy.LightState.BRIGHT,
                ScenarioPolicy.LightState.DARK));
        assertFalse("повтор того же состояния не срабатывает",
                ScenarioPolicy.matchesLight(scenario, ScenarioPolicy.LightState.DARK,
                        ScenarioPolicy.LightState.DARK));
    }

    @Test
    public void lightStateFollowsThresholdsWithHysteresis() {
        assertEquals(ScenarioPolicy.LightState.HOLD, ScenarioPolicy.lightState(-1));
        assertEquals(ScenarioPolicy.LightState.DARK, ScenarioPolicy.lightState(0));
        assertEquals(ScenarioPolicy.LightState.DARK, ScenarioPolicy.lightState(LightThresholds.DEFAULT_ON));
        assertEquals(ScenarioPolicy.LightState.HOLD, ScenarioPolicy.lightState(4));
        assertEquals(ScenarioPolicy.LightState.BRIGHT, ScenarioPolicy.lightState(6));
        assertEquals(ScenarioPolicy.LightState.BRIGHT, ScenarioPolicy.lightState(7));
    }

    @Test
    public void numericConditionsUseOperatorAndRejectUnknownValues() {
        ScenarioDefinition.Condition light = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_LIGHT, ScenarioProtocol.OP_LE, 3, "", "", "", "");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(light));
        assertTrue(ScenarioPolicy.conditionsMet(scenario, snapshot(2, 0, 50, null, 0, 1)));
        assertFalse(ScenarioPolicy.conditionsMet(scenario, snapshot(5, 0, 50, null, 0, 1)));
        assertFalse("неизвестный уровень не проходит",
                ScenarioPolicy.conditionsMet(scenario, snapshot(-1, 0, 50, null, 0, 1)));
    }

    @Test
    public void temperatureConditionRejectsMissingData() {
        ScenarioDefinition.Condition temp = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_TEMP_OUT, ScenarioProtocol.OP_LE, 0, "", "", "", "");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(temp));
        assertTrue(ScenarioPolicy.conditionsMet(scenario,
                snapshot(1, -5, 50, null, 0, 1)));
        assertFalse(ScenarioPolicy.conditionsMet(scenario,
                snapshot(1, ScenarioPolicy.Snapshot.UNKNOWN, 50, null, 0, 1)));
    }

    @Test
    public void driveModeConditionComparesNames() {
        ScenarioDefinition.Condition mode = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_DRIVE_MODE, "", 0, "SPORT", "", "", "");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(mode));
        assertTrue(ScenarioPolicy.conditionsMet(scenario, snapshot(1, 0, 50, "SPORT", 0, 1)));
        assertFalse(ScenarioPolicy.conditionsMet(scenario, snapshot(1, 0, 50, "ECO", 0, 1)));
        assertFalse("нет данных о режиме", ScenarioPolicy.conditionsMet(
                scenario, snapshot(1, 0, 50, null, 0, 1)));
    }

    @Test
    public void timeConditionSupportsOvernightWindowAndDays() {
        ScenarioDefinition.Condition night = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_TIME, "", 0, "", "23:00", "06:00", "");
        assertTrue(ScenarioPolicy.timeMatches(night, snapshot(1, 0, 50, null, 23 * 60 + 30, 1)));
        assertTrue("окно через полночь", ScenarioPolicy.timeMatches(night,
                snapshot(1, 0, 50, null, 2 * 60, 1)));
        assertFalse(ScenarioPolicy.timeMatches(night, snapshot(1, 0, 50, null, 12 * 60, 1)));

        ScenarioDefinition.Condition weekday = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_TIME, "", 0, "", "07:00", "10:00", "1,2,3,4,5");
        assertTrue(ScenarioPolicy.timeMatches(weekday, snapshot(1, 0, 50, null, 8 * 60, 3)));
        assertFalse("выходной не входит в список",
                ScenarioPolicy.timeMatches(weekday, snapshot(1, 0, 50, null, 8 * 60, 6)));
    }

    @Test
    public void daysMatchTreatsEmptyListAsEveryDay() {
        assertTrue(ScenarioPolicy.daysMatch("", 7));
        assertTrue(ScenarioPolicy.daysMatch(null, 1));
        assertTrue(ScenarioPolicy.daysMatch("2,4", 4));
        assertFalse(ScenarioPolicy.daysMatch("2,4", 5));
        assertTrue("мусор в списке не ломает остальные дни", ScenarioPolicy.daysMatch("x,3", 3));
    }

    @Test
    public void failingConditionIsDescribedWithCurrentValue() {
        ScenarioDefinition.Condition light = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_LIGHT, ScenarioProtocol.OP_LE, 3, "", "", "", "");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(light));
        assertNull(ScenarioPolicy.firstFailingCondition(scenario, snapshot(2, 0, 50, null, 0, 1)));

        String failure = ScenarioPolicy.firstFailingCondition(scenario, snapshot(5, 0, 50, null, 0, 1));
        assertNotNull(failure);
        assertTrue(failure.contains("освещённость"));
        assertTrue(failure.contains("≤ 3"));
        assertTrue("показано текущее значение", failure.contains("5"));

        ScenarioDefinition.Condition temp = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_TEMP_OUT, ScenarioProtocol.OP_LE, 0, "", "", "", "");
        ScenarioDefinition temperature = scenario(new ArrayList<>(), Collections.singletonList(temp));
        assertTrue("неизвестная температура без числа",
                ScenarioPolicy.firstFailingCondition(temperature,
                        snapshot(1, ScenarioPolicy.Snapshot.UNKNOWN, 50, null, 0, 1))
                        .contains("данных нет"));
    }

    @Test
    public void timeConditionDescriptionShowsWindowAndClock() {
        ScenarioDefinition.Condition time = new ScenarioDefinition.Condition(
                ScenarioProtocol.CONDITION_TIME, "", 0, "", "07:00", "10:00", "1,2");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(time));
        String failure = ScenarioPolicy.firstFailingCondition(scenario,
                snapshot(1, 0, 50, null, 12 * 60 + 34, 5));
        assertNotNull(failure);
        assertTrue(failure.contains("07:00–10:00"));
        assertTrue(failure.contains("дни 1,2"));
        assertTrue("показано текущее время", failure.contains("12:34"));
    }

    @Test
    public void unknownConditionIsNeverMet() {
        ScenarioDefinition.Condition mystery = new ScenarioDefinition.Condition(
                "mystery", ScenarioProtocol.OP_EQ, 0, "", "", "", "");
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(mystery));
        assertFalse(ScenarioPolicy.conditionsMet(scenario, snapshot(1, 0, 50, "SPORT", 0, 1)));
        assertNotNull("неизвестное условие тоже описывается",
                ScenarioPolicy.firstFailingCondition(scenario, snapshot(1, 0, 50, "SPORT", 0, 1)));
    }

    @Test
    public void hasConditionDetectsOnlyPresentTypes() {
        ScenarioDefinition scenario = scenario(new ArrayList<>(), Collections.singletonList(
                new ScenarioDefinition.Condition(ScenarioProtocol.CONDITION_SOC,
                        ScenarioProtocol.OP_GE, 20, "", "", "", "")));
        assertTrue(ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_SOC));
        assertFalse(ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_LIGHT));
        assertFalse(ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_TEMP_OUT));
        assertFalse(ScenarioPolicy.hasCondition(scenario, ScenarioProtocol.CONDITION_DRIVE_MODE));
    }

    @Test
    public void scenarioFlagsReflectContent() {
        List<ScenarioDefinition.Condition> lightCondition = Collections.singletonList(
                new ScenarioDefinition.Condition(ScenarioProtocol.CONDITION_LIGHT,
                        ScenarioProtocol.OP_GE, 5, "", "", "", ""));
        ScenarioDefinition scenario = scenario(new ArrayList<>(), lightCondition);
        assertTrue("условие по свету требует датчик", scenario.usesLight());
        assertFalse(scenario.hasEventTrigger());
        assertTrue(scenario.hasSteps());
    }
}
