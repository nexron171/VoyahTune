#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TRANSPORT="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/OemVehicleStateTransport.java"
TRACKER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/PowerHoldStatusTracker.java"
HUB="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/CanBusEventHub.java"
POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/PowerHoldPolicy.java"
CONTROLLER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/PowerHoldController.java"
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
NATIVE_MAIN="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/MainActivity.java"
RESTORE_MAIN="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/MainActivity.java"
RESTORE_LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout-land/activity_main.xml"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_fixed() { grep -Fq -- "$2" "$1" || fail "missing '$2' in $1"; }
forbid_fixed() {
    if grep -Fq -- "$2" "$1"; then
        fail "forbidden '$2' remains in $1"
    fi
}

# Android 11 ICanBusService ABI: TX6 reads GearState, TX57 reads a runtime-verified
# VehicleState, and TX77 sends the stock Power Hold bundle.
require_fixed "$TRANSPORT" 'private static final int TX_GEAR_STATUS = 6;'
require_fixed "$TRANSPORT" 'private static final int TX_GET_VEHICLE_STATE = 57;'
require_fixed "$TRANSPORT" 'private static final int TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE = 77;'
require_fixed "$TRANSPORT" 'static Map<StateKey, Integer> readVehicleStates('
require_fixed "$TRANSPORT" 'Integer readVehicleState(StateKey key);'
require_fixed "$TRANSPORT" 'Result sendBundle(Map<StateKey, Integer> values, String label);'
require_fixed "$TRANSPORT" 'private Integer transactVehicleState(IBinder binder, StateKey key)'
require_fixed "$TRANSPORT" 'data.writeInt(1); // VehicleState object is present.'
require_fixed "$TRANSPORT" 'data.writeInt(ordinal);'
require_fixed "$TRANSPORT" 'data.writeInt(key.stableId);'
require_fixed "$TRANSPORT" 'binder.transact(TX_GET_VEHICLE_STATE, data, reply, 0)'
require_fixed "$TRANSPORT" 'reply.readException();'
require_fixed "$TRANSPORT" 'int value = reply.readInt();'
require_fixed "$TRANSPORT" 'synchronized (transactionLock)'
require_fixed "$TRANSPORT" 'Enum.valueOf(vehicleStateClass, key.name)'
require_fixed "$TRANSPORT" 'if (!CanSender.beginFrameAttemptForCurrentGuard())'

# A status seed is a narrow, one-shot TX57 read. It must not create a second callback,
# request the vendor all-state TX20 snapshot, or own a timer/retry loop.
forbid_fixed "$TRANSPORT" 'TX_QUERY_VEHICLE_STATE'
forbid_fixed "$TRANSPORT" 'TX_ADD_CALLBACK'
forbid_fixed "$TRANSPORT" 'TX_REMOVE_CALLBACK'
forbid_fixed "$TRANSPORT" 'postDelayed('
forbid_fixed "$TRANSPORT" 'setInterval'

# Status uses the existing process-wide callback, an exact two-ID filter, a single seed per
# connection epoch, and one terminal confirmation timeout. It must not own another callback or
# repeat either the snapshot or timeout.
require_fixed "$TRACKER" 'CanBusEventHub.get(app).subscribe('
require_fixed "$TRACKER" 'CanBusEventRouter.INTEREST_CONNECTION'
require_fixed "$TRACKER" '| CanBusEventRouter.INTEREST_VEHICLE_STATE'
require_fixed "$TRACKER" 'new int[]{PowerHoldPolicy.POWER_HOLD_MODE_SWITCH_ID,'
require_fixed "$TRACKER" 'PowerHoldPolicy.POWER_HOLD_MODE_WARNING_ID}'
require_fixed "$TRACKER" 'static final long ACTIVATION_TIMEOUT_MS = 10_000L;'
require_fixed "$TRACKER" 'seedLoader.load(epoch,'
require_fixed "$TRACKER" 'seedRevision != liveRevision'
require_fixed "$TRACKER" 'scheduler.postDelayed(timeout, ACTIVATION_TIMEOUT_MS)'
require_fixed "$TRACKER" 'case CONNECTION_LOST:'
require_fixed "$HUB" 'router.dispatch(CanBusEvent.connectionLost('
forbid_fixed "$TRACKER" 'TX_QUERY_VEHICLE_STATE'
forbid_fixed "$TRACKER" 'TX_ADD_CALLBACK'
forbid_fixed "$TRACKER" 'TX_REMOVE_CALLBACK'
forbid_fixed "$TRACKER" 'postDelayed(this'
forbid_fixed "$TRACKER" 'setInterval'

# The stock controller performs exact P -> SOC -> one three-key TX77 operation. No raw frame may
# retain charge/regen/drive neighbours and no Power Hold OFF/lease belongs to VoyahTune.
require_fixed "$POLICY" 'static final int BMS_SOC_DISPLAY_ID = 615;'
require_fixed "$POLICY" 'static final int SCENE_MODE_EXTENDER_SET_ID = 1127;'
require_fixed "$POLICY" 'static final int POWER_HOLD_MODE_SWITCH_ID = 1161;'
require_fixed "$POLICY" 'static final int POWER_HOLD_MODE_TIME_ID = 1162;'
require_fixed "$POLICY" 'static final int POWER_HOLD_MODE_WARNING_ID = 1163;'
require_fixed "$POLICY" 'static final int MINIMUM_SOC_PERCENT = 15;'
require_fixed "$POLICY" 'static final int PERMANENT_DURATION = 15;'
require_fixed "$CONTROLLER" 'Gear gear = session.readGear();'
require_fixed "$CONTROLLER" 'Integer soc = session.readSoc();'
require_fixed "$CONTROLLER" 'return oemSession.sendBundle(keyed, label).accepted();'
forbid_fixed "$NATIVE_MAIN" 'LEAVE_CAR_FRAMES'
forbid_fixed "$NATIVE_MAIN" 'sendLeaveCarCommand'
forbid_fixed "$NATIVE_MAIN" '6c 08 00 3e 64 21 c7 00 00 00'
forbid_fixed "$NATIVE_MAIN" '77 08 00 00 00 00 00 1f 00 00'
forbid_fixed "$SERVICE" 'requestPowerHoldCleanup'
forbid_fixed "$SERVICE" 'restorePowerHold'

# IPC is package-targeted and signature protected in both directions. The card renders actual
# switch feedback instead of treating Binder acceptance as ACTIVE.
require_fixed "$SERVICE" 'ACTION_REQUEST_POWER_HOLD_STATUS'
require_fixed "$SERVICE" 'ACTION_POWER_HOLD_STATUS_UPDATE'
require_fixed "$SERVICE" 'tracker.beginActivation(requestGeneration -> {'
require_fixed "$SERVICE" 'tracker.finishActivation('
require_fixed "$SERVICE" 'update.setPackage(RESTOREMODE_PKG);'
require_fixed "$SERVICE" 'sendBroadcast(update, BIND_PERMISSION);'
require_fixed "$RESTORE_MAIN" 'new IntentFilter(ACTION_POWER_HOLD_STATUS_UPDATE)'
require_fixed "$RESTORE_MAIN" 'sendBroadcast(powerHoldRequest, BIND_SET_MODES_PERMISSION);'
require_fixed "$RESTORE_MAIN" 'case POWER_HOLD_ACTIVE:'
require_fixed "$RESTORE_LAYOUT" 'android:id="@+id/powerHoldBadge"'

# All four locally decompiled VehicleSettings variants expose the same H97C contract. Keep this
# comparison executable so a later firmware fixture cannot silently drift from the implementation.
FIRMWARE_GLOB="$REPO_ROOT/tmp/car_apks/decompiled"
REFERENCE_MANAGER=""
FIRMWARE_COUNT=0
for manager in "$FIRMWARE_GLOB"/*/sources/com/qinggan/scene/powerhold/PowerHoldModeManager.java; do
    [ -f "$manager" ] || continue
    FIRMWARE_COUNT=$((FIRMWARE_COUNT + 1))
    if [ -z "$REFERENCE_MANAGER" ]; then
        REFERENCE_MANAGER="$manager"
    elif ! cmp -s "$REFERENCE_MANAGER" "$manager"; then
        fail "PowerHoldModeManager firmware implementations diverged"
    fi
    vehicle_state=$(dirname "$(dirname "$(dirname "$manager")")")/canbus/VehicleState.java
    [ -f "$vehicle_state" ] || fail "missing VehicleState beside $manager"
    require_fixed "$vehicle_state" 'BMS_SOC_DISPLAY(615)'
    require_fixed "$vehicle_state" 'SCENE_MODE_EXTENDER_SET(1127)'
    require_fixed "$vehicle_state" 'POWER_HOLD_MODE_SWITCH(1161)'
    require_fixed "$vehicle_state" 'POWER_HOLD_MODE_TIME(1162)'
    require_fixed "$vehicle_state" 'POWER_HOLD_MODE_WARNING(1163)'
    require_fixed "$manager" 'this.mCanBusManager.getVehicleState(DFVehicleState.BMS_SOC_DISPLAY) < 15'
    require_fixed "$manager" 'bundle.putInt(VehicleState.POWER_HOLD_MODE_SWITCH.toString(), 1);'
    require_fixed "$manager" 'this.mCanBusManager.setVehicleAndAirConditionBundleState(null, bundle);'
done
[ "$FIRMWARE_COUNT" -eq 4 ] || fail "expected 4 Power Hold firmware fixtures, found $FIRMWARE_COUNT"

echo "PASS: Power Hold uses bounded OEM activation and shared event-driven status"
