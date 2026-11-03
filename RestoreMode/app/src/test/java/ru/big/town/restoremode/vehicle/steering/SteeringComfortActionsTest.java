package ru.big.town.restoremode.vehicle.steering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SteeringComfortActionsTest {
    @Test
    public void comfortActionsCanBeSelectedAndStoredInButtonSequence() {
        for (String prefix : Arrays.asList(
                "wheel_heat:", "seat:driver:heat:", "seat:driver:vent:", "seat:driver:massage:")) {
            List<String> actions = Arrays.asList(prefix + "on", prefix + "off", prefix + "toggle");
            for (String action : actions) {
                assertTrue(action, SteeringActions.isSelectable(action));
            }
            assertEquals(actions, SteeringActionStore.decode(SteeringActionStore.encode(actions)));
        }
    }

    @Test
    public void passengerAndMalformedComfortActionsCannotBeSelected() {
        for (String action : Arrays.asList(
                "seat:passenger:heat:on", "seat:passenger:vent:off",
                "seat:passenger:massage:toggle", "seat:driver:heat:unknown",
                "wheel_heat:toggle:extra")) {
            assertFalse(action, SteeringActions.isSelectable(action));
        }
    }
}
