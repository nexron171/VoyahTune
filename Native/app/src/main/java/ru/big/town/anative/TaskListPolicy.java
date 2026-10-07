package ru.big.town.anative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Отбор задач для «Диспетчера задач»: один пакет — одна запись, приоритет отдаётся задаче вне
 * виджета, чтобы тап по карточке переключал на видимую копию, а не на инстанс внутри
 * VirtualDisplay. Без Android-зависимостей, проверяется JVM-тестом {@code TaskListPolicyTest}.
 */
final class TaskListPolicy {
    /** displayId, если скрытое поле {@code RunningTaskInfo.displayId} прочитать не удалось. */
    static final int UNKNOWN_DISPLAY = -1;

    private TaskListPolicy() {}

    /** Кандидат из {@code getRunningTasks}: пакет, id задачи и дисплей, на котором она живёт. */
    static final class TaskCandidate {
        final String packageName;
        final int taskId;
        final int displayId;

        TaskCandidate(String packageName, int taskId, int displayId) {
            this.packageName = packageName;
            this.taskId = taskId;
            this.displayId = displayId;
        }
    }

    /** Выбранная запись: пакет, задача для переключения и признак «приложение внутри виджета». */
    static final class SelectedApp {
        final String packageName;
        final int taskId;
        final boolean widget;

        SelectedApp(String packageName, int taskId, boolean widget) {
            this.packageName = packageName;
            this.taskId = taskId;
            this.widget = widget;
        }
    }

    /**
     * Один пакет — одна запись, порядок — по первому появлению задачи ({@code getRunningTasks}
     * отдаёт задачи от свежей к старой). Если у пакета есть задача вне дисплеев виджетов, выбирается
     * она: тап по карточке должен переключать на видимую копию.
     */
    static List<SelectedApp> select(List<TaskCandidate> tasks, Set<Integer> widgetDisplays) {
        Map<String, SelectedApp> selected = new LinkedHashMap<>();
        for (TaskCandidate task : tasks) {
            if (task == null || task.packageName == null || task.packageName.isEmpty()) continue;
            boolean widget = isWidgetTask(task, widgetDisplays);
            SelectedApp known = selected.get(task.packageName);
            if (known != null && !(known.widget && !widget)) continue;
            selected.put(task.packageName, new SelectedApp(task.packageName, task.taskId, widget));
        }
        return new ArrayList<>(selected.values());
    }

    /**
     * Пакет запущен только внутри виджета: все его задачи на дисплеях виджетов, либо задач вовсе
     * нет, но пакет числится в реестре виджетов Native. Такую карточку нельзя переключать через
     * {@code moveTaskToFront} — задача осталась бы на своём VirtualDisplay.
     */
    static boolean widgetOnly(List<TaskCandidate> tasks, String packageName,
                              Set<Integer> widgetDisplays, boolean inWidgetRegistry) {
        if (packageName == null || packageName.isEmpty()) return false;
        boolean found = false;
        for (TaskCandidate task : tasks) {
            if (task == null || !packageName.equals(task.packageName)) continue;
            if (!isWidgetTask(task, widgetDisplays)) return false;
            found = true;
        }
        return found || inWidgetRegistry;
    }

    private static boolean isWidgetTask(TaskCandidate task, Set<Integer> widgetDisplays) {
        return widgetDisplays != null && task.displayId != UNKNOWN_DISPLAY
                && widgetDisplays.contains(task.displayId);
    }
}
