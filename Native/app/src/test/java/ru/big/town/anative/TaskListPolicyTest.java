package ru.big.town.anative;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class TaskListPolicyTest {
    private static final Set<Integer> WIDGETS = new HashSet<>(Arrays.asList(7, 8));

    private TaskListPolicy.TaskCandidate task(int id, String pkg, int display) {
        return new TaskListPolicy.TaskCandidate(pkg, id, display);
    }

    private String selected(List<TaskListPolicy.SelectedApp> apps) {
        StringBuilder text = new StringBuilder();
        for (TaskListPolicy.SelectedApp app : apps) {
            if (text.length() > 0) text.append(',');
            text.append(app.packageName).append(':').append(app.taskId).append(':').append(app.widget);
        }
        return text.toString();
    }

    @Test public void oneEntryPerPackageKeepsFirstTaskAndDisplayOrder() {
        assertEquals("navigator:10:false,media:12:false", selected(TaskListPolicy.select(Arrays.asList(
                task(10, "navigator", 0), task(11, "navigator", 0), task(12, "media", 0)), WIDGETS)));
    }

    @Test public void taskOutsideWidgetWinsOverWidgetInstance() {
        assertEquals("navigator:11:false", selected(TaskListPolicy.select(Arrays.asList(
                task(10, "navigator", 7), task(11, "navigator", 0)), WIDGETS)));
    }

    @Test public void widgetInstanceWinsWhenNothingElseIsRunning() {
        assertEquals("navigator:10:true", selected(TaskListPolicy.select(Arrays.asList(
                task(10, "navigator", 7), task(11, "navigator", 8)), WIDGETS)));
    }

    @Test public void unknownDisplayIsNotTreatedAsWidget() {
        assertEquals("navigator:10:false", selected(TaskListPolicy.select(Collections.singletonList(
                task(10, "navigator", TaskListPolicy.UNKNOWN_DISPLAY)), WIDGETS)));
    }

    @Test public void blankPackageIsSkipped() {
        assertTrue(TaskListPolicy.select(Arrays.asList(
                task(10, "", 0), task(11, null, 0)), WIDGETS).isEmpty());
    }

    @Test public void widgetOnlyIsTrueForTasksOnWidgetDisplays() {
        List<TaskListPolicy.TaskCandidate> tasks = Arrays.asList(
                task(10, "navigator", 7), task(11, "media", 0));
        assertTrue(TaskListPolicy.widgetOnly(tasks, "navigator", WIDGETS, false));
        assertFalse(TaskListPolicy.widgetOnly(tasks, "media", WIDGETS, false));
    }

    @Test public void widgetOnlyIsFalseWhenAnyTaskLeavesWidgetDisplay() {
        assertFalse(TaskListPolicy.widgetOnly(Arrays.asList(
                task(10, "navigator", 7), task(11, "navigator", 0)), "navigator", WIDGETS, false));
    }

    @Test public void widgetOnlyFallsBackToRegistryWhenTasksAreGone() {
        assertTrue(TaskListPolicy.widgetOnly(Collections.<TaskListPolicy.TaskCandidate>emptyList(),
                "navigator", WIDGETS, true));
        assertFalse(TaskListPolicy.widgetOnly(Collections.<TaskListPolicy.TaskCandidate>emptyList(),
                "navigator", WIDGETS, false));
    }

    @Test public void widgetOnlyIsFalseForBlankPackage() {
        assertFalse(TaskListPolicy.widgetOnly(Collections.<TaskListPolicy.TaskCandidate>emptyList(),
                null, WIDGETS, true));
    }
}
