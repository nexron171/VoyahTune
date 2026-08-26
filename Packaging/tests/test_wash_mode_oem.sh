#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TRANSPORT="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/OemVehicleStateTransport.java"
POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/WashModePolicy.java"
CONTROLLER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/WashModeController.java"
LEASE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/WashModeRequestLease.java"
SET_MODES="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
NATIVE_MAIN="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/MainActivity.java"
RESTORE_MAIN="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/MainActivity.java"
STRINGS="$REPO_ROOT/RestoreMode/app/src/main/res/values/strings.xml"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_fixed() { grep -Fq -- "$2" "$1" || fail "missing '$2' in $1"; }
forbid_fixed() {
    if grep -Fq -- "$2" "$1"; then
        fail "forbidden '$2' remains in $1"
    fi
}

# Android 11 ICanBusService ABI: TX6 returns GearState as present + ordinal + value. TX58 keeps
# runtime VehicleState name/stable-id verification and its final per-frame guard.
require_fixed "$TRANSPORT" 'private static final int TX_GEAR_STATUS = 6;'
require_fixed "$TRANSPORT" 'private static final int TX_SET_VEHICLE_STATE = 58;'
require_fixed "$TRANSPORT" 'binder.transact(TX_GEAR_STATUS, data, reply, 0)'
require_fixed "$TRANSPORT" 'reply.readException();'
require_fixed "$TRANSPORT" 'if (reply.readInt() == 0)'
require_fixed "$TRANSPORT" 'int ordinal = reply.readInt();'
require_fixed "$TRANSPORT" 'int value = reply.readInt();'
require_fixed "$TRANSPORT" 'static <T> T withSession('
require_fixed "$TRANSPORT" 'synchronized (transactionLock)'
require_fixed "$TRANSPORT" 'Enum.valueOf(vehicleStateClass, key.name)'
require_fixed "$TRANSPORT" 'if (!CanSender.beginFrameAttemptForCurrentGuard())'

# The wash contract is intentionally minimal: one OEM state, P gate, ON/OFF request values.
require_fixed "$POLICY" 'CLEANING_MODE = "CAR_CLEANING_MODE_SWITCH";'
require_fixed "$POLICY" 'CLEANING_MODE_ID = 1133;'
require_fixed "$POLICY" 'PARKING_ORDINAL = 0;'
require_fixed "$POLICY" 'CLEANING_ON = 1;'
require_fixed "$POLICY" 'CLEANING_OFF = 0;'
forbid_fixed "$POLICY" 'VEHICLE_TUNK'
forbid_fixed "$POLICY" 'SELF_CAR_WASH_STS'

forbid_fixed "$TRANSPORT" 'TX_GET_VEHICLE_STATE'
forbid_fixed "$TRANSPORT" 'postDelayed('
forbid_fixed "$TRANSPORT" 'setInterval'

# Confirmation is followed by one fire-and-forget activation. Native reads only the gear and sends
# only CAR_CLEANING_MODE_SWITCH; no trunk/status query, result IPC, or repeat-activation UI state.
require_fixed "$CONTROLLER" 'oemSession.readGearStatus()'
require_fixed "$CONTROLLER" 'long generation = lease.arm();'
require_fixed "$CONTROLLER" 'WashModePolicy.CLEANING_ON, "wash mode activate"'
require_fixed "$CONTROLLER" 'WashModePolicy.CLEANING_OFF, "wash mode cleanup: " + reason'
require_fixed "$SET_MODES" 'controller.activate();'
require_fixed "$SET_MODES" 'requestWashModeCleanup("SCREEN_OFF")'
require_fixed "$SET_MODES" 'requestWashModeCleanup("SCREEN_ON")'
require_fixed "$SET_MODES" 'requestWashModeCleanup("power state " + powerStateName(state))'
require_fixed "$RESTORE_MAIN" '.setMessage(R.string.wash_mode_confirmation)'
require_fixed "$RESTORE_MAIN" 'if (!ok) showSnack(getString(R.string.service_not_ready));'
require_fixed "$STRINGS" 'Автомобиль немедленно выключится.'
require_fixed "$STRINGS" 'Для выхода из режима нажмите педаль тормоза.'

for FILE in "$CONTROLLER" "$LEASE"; do
    forbid_fixed "$FILE" 'postDelayed('
    forbid_fixed "$FILE" 'setInterval'
    forbid_fixed "$FILE" 'VEHICLE_TUNK'
    forbid_fixed "$FILE" 'SELF_CAR_WASH_STS'
    forbid_fixed "$FILE" 'TX57'
    forbid_fixed "$FILE" 'TX77'
done
forbid_fixed "$SET_MODES" 'MSG_WASH_MODE_RESULT'
forbid_fixed "$RESTORE_MAIN" 'Режим мойки активирован'
forbid_fixed "$RESTORE_MAIN" 'washModeInFlight'
forbid_fixed "$RESTORE_MAIN" 'washRequestId'
forbid_fixed "$NATIVE_MAIN" 'WASH_MODE_FRAMES'
forbid_fixed "$NATIVE_MAIN" 'sendWashModeCommand'
forbid_fixed "$NATIVE_MAIN" 'CAR_CLEANING_MODE_SWITCH'

echo "PASS: wash mode transport uses bounded OEM TX6/TX58 session"
