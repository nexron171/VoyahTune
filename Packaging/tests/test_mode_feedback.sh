#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
CONTROLLER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeFeedbackController.java"
DECODER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeFeedbackDecoder.java"
VEHICLE_STATE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/VehicleStateControllers.java"
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
TRIPS="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/TripStatsService.java"
MODE_POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeSyncPolicy.java"
APPLY_ENGINE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApplyEngine.java"
NATIVE_MAIN="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/MainActivity.java"
ADVANCE="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
PROVIDER="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
ADVANCE_LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# The process-wide composition root owns the single shared vehicle-state subscription. The mode
# controller only decodes and applies mode feedback; it has no dependency on trip statistics.
require_fixed "$SERVICE" 'VehicleStateControllers.get(getApplicationContext())'
require_fixed "$VEHICLE_STATE" 'CanBusEventRouter.INTEREST_CONNECTION'
require_fixed "$VEHICLE_STATE" 'CanBusEventRouter.INTEREST_VEHICLE_STATE'
require_fixed "$VEHICLE_STATE" 'ModeFeedbackController.create(appContext, stateHandler)'
require_fixed "$DECODER" 'RECYCLE_MODE_VSTATE_ID = VehicleRestorePolicy.REGEN_LEVEL_ID'
require_fixed "$CONTROLLER" 'ModeFeedbackDecoder.decode(id, state)'
require_fixed "$CONTROLLER" 'ApplyEngine.persistModeFeedbackIfAllowed('
require_fixed "$CONTROLLER" 'new IntentFilter(ACTION_REMEMBER_LAST_CHANGED), BIND_PERMISSION'

if grep -Eq 'ModeFeedback|MODE_REMEMBER|persistModeFeedback|INTEREST_VEHICLE_STATE' "$TRIPS"; then
    fail "TripStatsService contains vehicle-mode responsibilities"
fi

# Feedback needs first Drive as well as a completed pass, and never causes correction retries.
require_fixed "$MODE_POLICY" 'feedbackOpen && acceptsExternalFeedback(modeKey)'
require_fixed "$VEHICLE_STATE" 'ApplyEngine.noteDriverDoorOpened();'
require_fixed "$VEHICLE_STATE" 'ApplyEngine.noteGear(event.first);'
require_fixed "$MODE_POLICY" 'canRememberSelection()'
require_fixed "$NATIVE_MAIN" 'if (!ApplyEngine.canRememberModeSelection()) return;'
require_fixed "$APPLY_ENGINE" 'MODE_SYNC_POLICY.canPersist('
require_fixed "$NATIVE_MAIN" '!remembersMode(context, modeKey)'
require_fixed "$PROVIDER" 'sharedPreferences.getBoolean(rememberKey, true)'
require_fixed "$ADVANCE" 'if (!prefs.getBoolean(rememberKey, true)) return;'

# Remember-last is opt-out per mode. Missing provider columns, NULL values and old caches all retain
# the historical enabled behaviour; the running controller receives UI changes immediately.
[ "$(grep -F -c 'android:text="Запоминать последнее выбранное значение"' "$ADVANCE_LAYOUT")" -eq 3 ] \
    || fail "remember-last switch must be shown once for each mode"
for key in driveRememberLast energyRememberLast recycleRememberLast; do
    require_fixed "$PROVIDER" "sharedPreferences.getBoolean(\"$key\","
    require_fixed "$PROVIDER" "\"$key\","
    require_fixed "$ADVANCE" "\"$key\""
done
require_fixed "$NATIVE_MAIN" 'cursor.getColumnCount() <= column || cursor.isNull(column)'
require_fixed "$NATIVE_MAIN" 'p.getBoolean("cacheDriveRememberLast", true)'
require_fixed "$NATIVE_MAIN" 'p.getBoolean("cacheEnergyRememberLast", true)'
require_fixed "$NATIVE_MAIN" 'p.getBoolean("cacheRecycleRememberLast", true)'
require_fixed "$ADVANCE" 'ru.big.town.anative.MODE_REMEMBER_CHANGED'

echo "PASS: vehicle-mode feedback is isolated, wake-safe and remember-last is opt-out"
