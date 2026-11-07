package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.ScenarioProtocol;

import java.util.Collections;

public class ScenarioSpeedPolicyTest {
    @Test
    public void triggerFiresOnlyWhenComparisonBecomesTrue() {
        ScenarioDefinition.Trigger accelerating = trigger("50", "ge");
        assertTrue(ScenarioPolicy.matchesSpeed(accelerating, 49, 50));
        assertTrue(ScenarioPolicy.matchesSpeed(accelerating, 49, 51));
        assertFalse(ScenarioPolicy.matchesSpeed(accelerating, 50, 51));
        assertFalse(ScenarioPolicy.matchesSpeed(accelerating, 51, 49));
        assertTrue(ScenarioPolicy.matchesSpeed(accelerating, 49, 50));

        ScenarioDefinition.Trigger slowing = trigger("50", "le");
        assertTrue(ScenarioPolicy.matchesSpeed(slowing, 51, 50));
        assertTrue(ScenarioPolicy.matchesSpeed(slowing, 51, 49));
        assertFalse(ScenarioPolicy.matchesSpeed(slowing, 50, 49));
        assertFalse(ScenarioPolicy.matchesSpeed(slowing, 49, 51));

        ScenarioDefinition.Trigger exact = trigger("50", "eq");
        assertTrue(ScenarioPolicy.matchesSpeed(exact, 49, 50));
        assertTrue(ScenarioPolicy.matchesSpeed(exact, 51, 50));
        assertFalse(ScenarioPolicy.matchesSpeed(exact, 50, 50));
        assertFalse(ScenarioPolicy.matchesSpeed(exact, 49, 51));
    }

    @Test
    public void triggerRequiresKnownReadingsAndSupportsBoundaryThresholds() {
        for (String operator : new String[] {"le", "ge", "eq"}) {
            assertFalse(ScenarioPolicy.matchesSpeed(trigger("50", operator), -1, 50));
            assertFalse(ScenarioPolicy.matchesSpeed(trigger("50", operator), 50, -1));
        }
        assertTrue(ScenarioPolicy.matchesSpeed(trigger("0", "le"), 1, 0));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("0", "le"), 0, 0));
        assertTrue(ScenarioPolicy.matchesSpeed(trigger("190", "ge"), 189, 190));
        assertTrue(ScenarioPolicy.matchesSpeed(trigger("190", "ge"), 189, 200));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("190", "ge"), 190, 200));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("195", "ge"), 190, 200));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("3", "ge"), 0, 5));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("bad", "ge"), 0, 50));
        assertFalse(ScenarioPolicy.matchesSpeed(trigger("50", "bad"), 0, 50));
        assertFalse(ScenarioPolicy.matchesSpeed(
                new ScenarioDefinition.Trigger("tempIn", "20", "ge"), 19, 20));
        assertFalse(ScenarioPolicy.matchesCabinTemperature(trigger("20", "ge"), 19, 20));
    }

    @Test
    public void conditionsCompareSpeedAndRejectUnknownReadings() {
        for (String operator : new String[] {"le", "ge", "eq"}) {
            ScenarioDefinition scenario = scenario(operator, 50);
            assertTrue(ScenarioPolicy.conditionsMet(scenario, snapshot(50)));
            assertEquals("le".equals(operator), ScenarioPolicy.conditionsMet(scenario, snapshot(49)));
            assertEquals("ge".equals(operator), ScenarioPolicy.conditionsMet(scenario, snapshot(51)));
            assertFalse(ScenarioPolicy.conditionsMet(scenario, snapshot(-1)));
            assertTrue(ScenarioPolicy.firstFailingCondition(scenario, snapshot(-1))
                    .contains("данных нет"));
        }
        assertTrue(ScenarioPolicy.conditionsMet(scenario("le", 0), snapshot(0)));
        assertFalse(ScenarioPolicy.conditionsMet(scenario("le", 0), snapshot(1)));
        assertTrue(ScenarioPolicy.conditionsMet(scenario("ge", 190), snapshot(200)));
        assertEquals(
                "скорость (нужно ≤ 50 км/ч, сейчас 51 км/ч)",
                ScenarioPolicy.firstFailingCondition(scenario("le", 50), snapshot(51)));
    }

    private static ScenarioDefinition.Trigger trigger(String threshold, String operator) {
        return new ScenarioDefinition.Trigger("speed", threshold, operator);
    }

    private static ScenarioDefinition scenario(String operator, int threshold) {
        return new ScenarioDefinition(
                "speed",
                "Скорость",
                true,
                false,
                Collections.emptyList(),
                Collections.singletonList(new ScenarioDefinition.Condition(
                        ScenarioProtocol.CONDITION_SPEED, operator, threshold, "", "", "", "")),
                Collections.emptyList());
    }

    private static ScenarioPolicy.Snapshot snapshot(int speedKmh) {
        return new ScenarioPolicy.Snapshot(-1, ScenarioPolicy.Snapshot.UNKNOWN,
                -1, null, 0, 1, -1, Double.NaN, speedKmh);
    }
}
