package ru.big.town.anative;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.*;

public class AppDisplayTaskPlanTest {
    private SplitHostTaskSnapshot.TaskRecord task(int id, String pkg, Integer display) {
        return new SplitHostTaskSnapshot.TaskRecord(id, pkg, display);
    }

    @Test public void returningToClickedPassengerScreenRetiresOnlyOldNavigatorTasks() {
        Set<Integer> retiring = AppDisplayTaskPlan.retiring(Arrays.asList(
                task(10, "navigator", 7), task(11, "navigator", 0),
                task(12, "navigator", 1), task(13, "media", 5)), "navigator", 1);
        assertEquals(new java.util.HashSet<>(Arrays.asList(10, 11)), retiring);
    }

    @Test public void enteringClusterRetiresPhysicalTaskWithoutTouchingStockCard() {
        assertEquals(Collections.singleton(10), AppDisplayTaskPlan.retiring(Arrays.asList(
                task(10, "navigator", 0), task(11, "media", 5)), "navigator", 7));
    }

    @Test public void repeatedTapOnSameScreenPreservesTask() {
        assertTrue(AppDisplayTaskPlan.retiring(Collections.singletonList(
                task(10, "navigator", 0)), "navigator", 0).isEmpty());
    }

    @Test(expected = IllegalStateException.class) public void unknownDisplayAbortsBeforeRemovingAnyTasks() {
        AppDisplayTaskPlan.retiring(Arrays.asList(task(10, "navigator", 7),
                task(11, "navigator", null)), "navigator", 0);
    }

    @Test public void unrelatedUnknownDisplayDoesNotBlockReturn() {
        assertEquals(Collections.singleton(10), AppDisplayTaskPlan.retiring(Arrays.asList(
                task(10, "navigator", 7), task(11, "media", null)), "navigator", 0));
    }
}
