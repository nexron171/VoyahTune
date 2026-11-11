package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PhysicalWakeActionsTest {
    private final List<String> actions = new ArrayList<>();
    private final PhysicalWakeActions wake =
            new PhysicalWakeActions(
                    () -> actions.add("battery"), () -> actions.add("screen"));

    @Test
    public void remoteWakeStartsBatteryHeatWithTheScreenOff() {
        wake.onWake(false);

        assertEquals(Arrays.asList("battery"), actions);
    }

    @Test
    public void repeatedEarlyStatesAndScreenOnRunEachActionOnce() {
        wake.onWake(false);
        wake.onWake(false);
        assertEquals(Arrays.asList("battery"), actions);

        wake.onScreenOn();
        wake.onWake(true);
        wake.onScreenOn();

        assertEquals(Arrays.asList("battery", "screen"), actions);
    }

    @Test
    public void wakeWithTheScreenReadyRunsBothActionsInOrder() {
        wake.onWake(true);
        wake.onWake(true);

        assertEquals(Arrays.asList("battery", "screen"), actions);
    }

    @Test
    public void screenOnWithoutPhysicalWakeDoesNotStartActions() {
        wake.onScreenOn();

        assertTrue(actions.isEmpty());
    }

    @Test
    public void sleepCancelsDeferredScreenWorkAndAllowsANewRemoteWake() {
        wake.onWake(false);
        wake.onSleep();
        wake.onScreenOn();
        assertEquals(Arrays.asList("battery"), actions);

        wake.onWake(false);
        wake.onScreenOn();

        assertEquals(Arrays.asList("battery", "battery", "screen"), actions);
    }

    @Test
    public void completedWakeCanStartAgainAfterSleep() {
        wake.onWake(true);
        wake.onSleep();
        wake.onWake(true);

        assertEquals(Arrays.asList("battery", "screen", "battery", "screen"), actions);
    }
}
