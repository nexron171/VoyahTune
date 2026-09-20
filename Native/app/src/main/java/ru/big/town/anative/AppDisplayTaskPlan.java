package ru.big.town.anative;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Compute the complete handoff before removing anything; unknown display IDs fail closed. */
final class AppDisplayTaskPlan {
    private AppDisplayTaskPlan() {}

    static Set<Integer> retiring(List<SplitHostTaskSnapshot.TaskRecord> tasks, String pkg, int target) {
        Set<Integer> result = new LinkedHashSet<>();
        for (SplitHostTaskSnapshot.TaskRecord task : tasks) {
            if (!pkg.equals(task.packageName)) continue;
            if (task.displayId == null) throw new IllegalStateException("Unknown display for task " + task.taskId);
            if (task.displayId != target) result.add(task.taskId);
        }
        return result;
    }
}
