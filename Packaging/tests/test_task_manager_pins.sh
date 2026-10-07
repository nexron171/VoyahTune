#!/bin/sh
# Контракт фиксации приложений в «Диспетчере задач»: булавка на карточке, хранение в Native и пропуск
# зафиксированных во всех путях «Закрыть все».
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROTOCOL="$ROOT/SharedAndroid/src/main/java/ru/big/town/common/TaskManagerProtocol.java"
WIDGETS="$ROOT/Native/app/src/main/java/ru/big/town/anative/WidgetSupport.java"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
POLICY="$ROOT/Native/app/src/main/java/ru/big/town/anative/PinnedAppsPolicy.java"
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/TaskManagerActivity.java"
CARD="$ROOT/RestoreMode/app/src/main/res/layout/item_task_card.xml"
PIN_ICON="$ROOT/RestoreMode/app/src/main/res/drawable/ic_pin.xml"
DIALOG="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"

fail() {
    echo "task manager pins contract test failed: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

# Протокол: отдельное сообщение фиксации и признак в ответе списка.
require_fixed "$PROTOCOL" 'public static final int PIN = 109;'
require_fixed "$PROTOCOL" 'public static final String PINNED = "pinned";'

# Native хранит список фиксаций и отдаёт его параллельным массивом.
require_fixed "$WIDGETS" 'static final String PREF_PINNED_APPS = "pinnedApps";'
require_fixed "$SERVICE" 'data.putBooleanArray(ru.big.town.common.TaskManagerProtocol.PINNED, pinned);'
require_fixed "$SERVICE" 'replyTaskList(msg.replyTo);'
require_fixed "$POLICY" 'static String setPinned(String csv, String pkg, boolean pinned) {'

# Оба пути «Закрыть все» щадят зафиксированные приложения.
require_fixed "$SERVICE" 'if (WidgetSupport.isPinned(this, pkg)) { pinned++; continue; }'
require_fixed "$WIDGETS" 'if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !isPinned(context, info.packageName)) {'
require_fixed "$SERVICE" 'pinTaskApp(pkg, pinned);'
require_fixed "$SERVICE" 'WidgetSupport.setPinned(this, pkg, pinned);'

# Диалог «Закрыть приложения» предупреждает, что зафиксированные останутся открытыми.
require_fixed "$DIALOG" 'зафиксированные в «Диспетчере задач», останутся открытыми'

# UI: булавка в карточке, перерисовка состояния и отправка PIN.
require_fixed "$CARD" 'android:id="@+id/taskCardPin"'
require_fixed "$CARD" 'android:src="@drawable/ic_pin"'
[ -s "$PIN_ICON" ] || fail "pin drawable is missing"
require_fixed "$ACTIVITY" 'pin.setOnClickListener(v -> sendPin(pkg, !pinned));'
require_fixed "$ACTIVITY" 'if (!send(TaskManagerProtocol.PIN, pkg, pinned)) {'
require_fixed "$ACTIVITY" 'cardPinned.add(pinned != null && i < pinned.length && pinned[i]);'
# Обычная «Закрыть» фиксацию игнорирует — она закрывает приложение всегда.
require_fixed "$ACTIVITY" 'card.findViewById(R.id.taskCardClose).setOnClickListener(v -> sendClose(pkg));'

# Приложения внутри виджетов: признак в ответе списка, реестр Native и переключение на экран.
TASK_POLICY="$ROOT/Native/app/src/main/java/ru/big/town/anative/TaskListPolicy.java"
require_fixed "$PROTOCOL" 'public static final String WIDGETS = "widgets";'
require_fixed "$SERVICE" 'data.putBooleanArray(ru.big.town.common.TaskManagerProtocol.WIDGETS, widget);'
require_fixed "$SERVICE" 'WidgetSupport.runningApps(this, widgetDisplayIds());'
require_fixed "$SERVICE" 'isWidgetOnlyTask(pkg)'
require_fixed "$SERVICE" 'WidgetSupport.isWidgetOnly(this, pkg, widgetDisplayIds(),'
require_fixed "$WIDGETS" 'static boolean isWidgetOnly(Context context, String packageName, Set<Integer> widgetDisplays,'
require_fixed "$TASK_POLICY" 'static List<SelectedApp> select(List<TaskCandidate> tasks, Set<Integer> widgetDisplays) {'
require_fixed "$CARD" 'android:id="@+id/taskCardWidget"'
require_fixed "$ACTIVITY" 'card.findViewById(R.id.taskCardWidget).setVisibility(widget ? View.VISIBLE : View.INVISIBLE);'

echo "task manager pins contract test passed"
