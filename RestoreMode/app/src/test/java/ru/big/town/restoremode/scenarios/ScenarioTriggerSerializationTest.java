package ru.big.town.restoremode.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ScenarioTriggerSerializationTest {
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
