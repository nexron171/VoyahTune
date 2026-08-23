package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android-free normalized view of one ActivityManager task query. */
final class SplitHostTaskSnapshot {
    static final class TaskRecord {
        final int taskId;
        final String packageName;
        final Integer displayId;

        TaskRecord(int taskId, String packageName, Integer displayId) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.displayId = displayId;
        }
    }

    private static final class PackageState {
        final Set<Integer> displayIds = new HashSet<>();
        boolean hasUnknownDisplay;
    }

    private final boolean known;
    private final List<TaskRecord> tasks;
    private final Map<String, PackageState> packages;

    private SplitHostTaskSnapshot(boolean known, List<TaskRecord> taskRecords) {
        this.known = known;
        this.tasks = Collections.unmodifiableList(new ArrayList<>(taskRecords));
        this.packages = new HashMap<>();
        if (!known) return;
        for (TaskRecord task : taskRecords) {
            if (task == null || task.packageName == null || task.packageName.isEmpty()) continue;
            PackageState state = packages.get(task.packageName);
            if (state == null) {
                state = new PackageState();
                packages.put(task.packageName, state);
            }
            if (task.displayId == null) state.hasUnknownDisplay = true;
            else state.displayIds.add(task.displayId);
        }
    }

    static SplitHostTaskSnapshot unknown() {
        return new SplitHostTaskSnapshot(false, Collections.emptyList());
    }

    static SplitHostTaskSnapshot known(List<TaskRecord> tasks) {
        return new SplitHostTaskSnapshot(true,
                tasks == null ? Collections.emptyList() : tasks);
    }

    /**
     * Fail-open parity with the old watchdog: an unavailable snapshot or an unreadable display id
     * means "alive". A known task on another display only means the pane is dead.
     */
    boolean isAlive(String packageName, int displayId) {
        if (!known || packageName == null || packageName.isEmpty()) return true;
        PackageState state = packages.get(packageName);
        if (state == null) return false;
        return state.hasUnknownDisplay || state.displayIds.contains(displayId);
    }

    List<TaskRecord> tasks() {
        return tasks;
    }
}
