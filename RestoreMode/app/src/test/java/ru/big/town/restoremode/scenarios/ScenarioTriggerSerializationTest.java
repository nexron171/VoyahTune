package ru.big.town.restoremode.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ScenarioTriggerSerializationTest {
    @Test
    public void speedRoundTripKeepsThresholdsComparisonsAndFirstEventSelection() {
        ScenarioStore.Scenario scenario = new ScenarioStore.Scenario();
        scenario.id = "speed";
        for (String operator : new String[] {"le", "ge", "eq"}) {
            for (int threshold : new int[] {0, 5, 95, 190}) {
                ScenarioStore.Trigger trigger =
                        new ScenarioStore.Trigger("speed", String.valueOf(threshold), operator);
                trigger.firstOnly = true;
                scenario.triggers.add(trigger);
                ScenarioStore.Condition condition = new ScenarioStore.Condition();
                condition.type = "speed";
                condition.op = operator;
                condition.value = threshold;
                scenario.conditions.add(condition);
            }
        }

        String json = ScenarioStore.toJson(Collections.singletonList(scenario));
        ScenarioStore.Scenario restored = ScenarioStore.fromJson(json).get(0);
        assertEquals(12, restored.triggers.size());
        assertEquals(12, restored.conditions.size());
        for (int index = 0; index < scenario.triggers.size(); index++) {
            ScenarioStore.Trigger expected = scenario.triggers.get(index);
            ScenarioStore.Trigger actual = restored.triggers.get(index);
            assertEquals(expected.type, actual.type);
            assertEquals(expected.value, actual.value);
            assertEquals(expected.edge, actual.edge);
            assertTrue(actual.firstOnly);
            ScenarioStore.Condition condition = restored.conditions.get(index);
            assertEquals("speed", condition.type);
            assertEquals(expected.edge, condition.op);
            assertEquals(Integer.parseInt(expected.value), condition.value);
        }
    }

    @Test
    public void speedRoundTripDropsInvalidThresholdsAndOperators() {
        ScenarioStore.Scenario scenario = new ScenarioStore.Scenario();
        scenario.id = "invalid-speed";
        for (String threshold : new String[] {"-5", "195", "3", "", "bad", "5.5"}) {
            scenario.triggers.add(new ScenarioStore.Trigger("speed", threshold, "ge"));
        }
        scenario.triggers.add(new ScenarioStore.Trigger("speed", "50", "bad"));
        for (int threshold : new int[] {-5, 195, 3}) {
            ScenarioStore.Condition condition = new ScenarioStore.Condition();
            condition.type = "speed";
            condition.value = threshold;
            scenario.conditions.add(condition);
        }
        ScenarioStore.Condition condition = new ScenarioStore.Condition();
        condition.type = "speed";
        condition.value = 50;
        condition.op = "bad";
        scenario.conditions.add(condition);

        ScenarioStore.Scenario restored = ScenarioStore.fromJson(
                ScenarioStore.toJson(Collections.singletonList(scenario))).get(0);
        assertTrue(restored.triggers.isEmpty());
        assertTrue(restored.conditions.isEmpty());
    }

    @Test
    public void cabinTriggerRoundTripKeepsOnlyValidThresholdsAndOperators() {
        ScenarioStore.Scenario scenario = new ScenarioStore.Scenario();
        scenario.id = "climate";
        for (String threshold : new String[] {"-40", "85", "", "bad", "-41", "86"}) {
            scenario.triggers.add(new ScenarioStore.Trigger("tempIn", threshold, "ge"));
        }
        scenario.triggers.add(new ScenarioStore.Trigger("tempIn", "20", "bad"));
        scenario.triggers.get(0).firstOnly = true;

        String json = ScenarioStore.toJson(Collections.singletonList(scenario));
        ScenarioStore.Scenario restored = ScenarioStore.fromJson(json).get(0);
        assertEquals(2, restored.triggers.size());
        assertEquals("-40", restored.triggers.get(0).value);
        assertEquals("ge", restored.triggers.get(0).edge);
        assertTrue(restored.triggers.get(0).firstOnly);
        assertEquals("85", restored.triggers.get(1).value);
    }

    @Test
    public void missingFlagKeepsEveryEventAndRoundTripPreservesSelection() {
        String legacy = "[{\"id\":\"one\",\"name\":\"Test\",\"triggers\":["
                + "{\"type\":\"gear\",\"value\":\"D\"}]}]";
        ScenarioStore.Scenario scenario = ScenarioStore.fromJson(legacy).get(0);
        assertFalse(scenario.triggers.get(0).firstOnly);
        for (boolean firstOnly : new boolean[] {true, false}) {
            scenario.triggers.get(0).firstOnly = firstOnly;
            String json = ScenarioStore.toJson(Collections.singletonList(scenario));
            ScenarioStore.Trigger restored = ScenarioStore.fromJson(json).get(0).triggers.get(0);
            assertEquals(firstOnly, restored.firstOnly);
        }
    }
}
