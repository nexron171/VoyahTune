package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SteeringActionStoreTest {
    @Test
    public void roundTripsOrderedActionsWithoutDelimiterRestrictions() {
        String encoded = SteeringActionStore.encode(Arrays.asList(
                "toggle_forced_ev", "split:left|odd,right,1", "can:64088000000000000003"));

        assertEquals(Arrays.asList(
                        "toggle_forced_ev", "split:left|odd,right,1", "can:64088000000000000003"),
                SteeringActionStore.decode(encoded));
    }

    @Test
    public void readsLegacySingleAction() {
        assertEquals(Collections.singletonList("energy:EV,REV"),
                SteeringActionStore.decode("energy:EV,REV"));
    }

    @Test
    public void noneAndBrokenSequenceAreEmpty() {
        assertTrue(SteeringActionStore.decode("none").isEmpty());
        assertTrue(SteeringActionStore.decode("steer-actions-v1:9:short").isEmpty());
    }
}
