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
