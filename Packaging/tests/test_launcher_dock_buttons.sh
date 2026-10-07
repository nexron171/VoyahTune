#!/bin/sh
# Контракт кнопок родного дока: Home (тап → VoyahTune, долгий тап → штатный home) и нижняя кнопка
# «меню» (долгий тап → «Диспетчер задач»).
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
DOCK="$ROOT/Packaging/inject/launcherdock.js"

fail() {
    echo "launcher dock buttons contract test failed: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

node --check "$DOCK"

# Домашний элемент водительского дока: разрешается в dockViews() и сверяется при клике.
require_fixed "$DOCK" 'home: field(bar, "mScreenUpHomeView"),'
[ "$(grep -Fc '"mScreenUpHomeView"' "$DOCK")" -eq 2 ] \
    || fail "home view must be resolved in dockViews() and checked in onClick"

# Штатный home запускается явным компонентом, с фолбэком без компонента.
require_fixed "$DOCK" 'var LAUNCHER_HOME_ACT = "com.qinggan.app.launcher.activity.MainActivity";'
require_fixed "$DOCK" 'if (explicit) i.setClassName(LAUNCHER_PKG, LAUNCHER_HOME_ACT);'
require_fixed "$DOCK" 'i.setAction(Intent.ACTION_MAIN.value);'
require_fixed "$DOCK" 'i.addCategory(Intent.CATEGORY_HOME.value);'
[ "$(grep -Fc 'i.addFlags(0x10000000 | 0x04000000 | 0x20000000);' "$DOCK")" -eq 3 ] \
    || fail "VoyahTune, stock home and the task manager must share NEW_TASK|CLEAR_TOP|SINGLE_TOP"

# Долгий тап по Home идёт через отдельный слушатель и гасит штатное долгое.
require_fixed "$DOCK" 'name: "ru.big.town.dock.HomeLongClick",'
require_fixed "$DOCK" 'implementation: function (view) { stockLauncherHome(); return true; }'
require_fixed "$DOCK" 'views.home.setOnLongClickListener(homeLC);'
[ "$(grep -Fc 'views.home.setOnLongClickListener(homeLC);' "$DOCK")" -eq 1 ] \
    || fail "home long-press listener must be attached from one place only"

# Короткий тап по Home открывает VoyahTune и НЕ вызывает штатный onClick.
require_fixed "$DOCK" 'openVoyahTune("home tap");'
grep -A1 -F 'openVoyahTune("home tap");' "$DOCK" | grep -Fq 'return;' \
    || fail "home tap must return without calling the stock onClick"
# Долгий тап по нижней кнопке дока («меню») открывает «Диспетчер задач» вместо главного экрана.
require_fixed "$DOCK" 'var RESTORE_TASK_ACT = "ru.big.town.restoremode.TaskManagerActivity";'
require_fixed "$DOCK" 'i.setClassName(RESTORE_PKG, RESTORE_TASK_ACT);'
require_fixed "$DOCK" 'implementation: function (view) { openTaskManager(); return true; }'
if grep -Fq 'openVoyahTune("menu long-press")' "$DOCK"; then
    fail "menu long-press must open the task manager, not the VoyahTune main screen"
fi

# Диспетчер отступает под полосу родного дока → хук обязан оставить док поверх него.
require_fixed "$DOCK" '            || act.indexOf("TaskManagerActivity") >= 0'

# Перехват живёт только в водительской ветке onClick: после гарда screenOf != 0 и до веток слотов.
guard_line=$(grep -nF 'if (screenOf(this) !== 0) return mainOnClick.call(this, view);' "$DOCK" | cut -d: -f1)
home_line=$(grep -nF 'openVoyahTune("home tap");' "$DOCK" | cut -d: -f1)
slot1_line=$(grep -nF 'for (var slot = 1; slot <= 2; slot++) {' "$DOCK" | cut -d: -f1)
[ -n "$guard_line" ] || fail "driver-only guard not found in onClick"
[ -n "$home_line" ] || fail "home tap branch not found"
[ -n "$slot1_line" ] || fail "slot branch not found"
[ "$guard_line" -lt "$home_line" ] \
    || fail "home remap must sit after the passenger/driver guard"
[ "$home_line" -lt "$slot1_line" ] \
    || fail "home remap must be evaluated before the slot branches"

# Пассажирский бар не переназначается: слушатель вешается в updateIcons после его гарда.
update_line=$(grep -nF 'function updateIcons(bar, skipLayout) {' "$DOCK" | cut -d: -f1)
update_guard_line=$(awk -v start="$update_line" 'NR >= start && /if \(screenOf\(bar\) !== 0\) return;/ { print NR; exit }' "$DOCK")
home_attach_line=$(grep -nF 'views.home.setOnLongClickListener(homeLC);' "$DOCK" | cut -d: -f1)
[ -n "$update_guard_line" ] || fail "updateIcons lost its driver-only guard"
[ "$update_guard_line" -lt "$home_attach_line" ] \
    || fail "home long-press attach must sit after the updateIcons guard"

echo "launcher dock buttons contract test passed"
