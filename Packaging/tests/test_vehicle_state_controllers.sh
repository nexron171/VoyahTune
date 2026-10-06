#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
STATE_ROOT="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative"
COMPOSITION="$STATE_ROOT/VehicleStateControllers.java"
GEAR="$STATE_ROOT/GearStateController.java"
DOOR="$STATE_ROOT/DriverDoorStateController.java"
TRIPS="$STATE_ROOT/TripStatsService.java"
WIPERS="$STATE_ROOT/WiperColdService.java"
LIGHT="$STATE_ROOT/LightSensorService.java"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# One composition root routes typed domain state from the shared CAN hub.
[ "$(grep -F -c 'CanBusEventHub.get(' "$COMPOSITION")" -eq 1 ] \
    || fail "VehicleStateControllers must have one CanBusEventHub acquisition"
require_fixed "$COMPOSITION" 'CanBusEventRouter.INTEREST_DOOR'
require_fixed "$COMPOSITION" 'CanBusEventRouter.INTEREST_GEAR'
require_fixed "$COMPOSITION" 'gearStateController.accept(event.first);'
require_fixed "$COMPOSITION" 'driverDoorStateController.accept('
require_fixed "$COMPOSITION" 'canBusEventHub.requestDriverDoorSeed();'
require_fixed "$COMPOSITION" 'InfrastructureProfile.read(context).usesAccHooks()'

# PI's native restore observes the same typed transport; OD is driven only by ACC hooks.
# Check each branch separately so moving a trigger outside its profile guard fails.
for event in DOOR GEAR; do
    guard='if (!accHooks)'
    if [ "$event" = GEAR ]; then
        guard='if (!accHooks && event.origin == CanBusEvent.Origin.LIVE)'
    fi
    awk -v event="$event" -v guard="$guard" '
        $0 ~ "case " event ":" { inside = 1; next }
        inside && index($0, guard) { guardDepth = depth + 1 }
        inside && /ApplyEngine\.(noteDriverDoorOpened|noteGear|scheduleNativeApply)/ {
            if (!guardDepth || depth < guardDepth) exit 1
            found = 1
        }
        inside {
            line = $0
            depth += gsub(/\{/, "{", line) - gsub(/\}/, "}", line)
            if (depth < guardDepth) guardDepth = 0
        }
        inside && /break;/ { exit(found ? 0 : 1) }
        END { if (!found) exit 1 }
    ' "$COMPOSITION" || fail "$event native restore is missing or not restricted to PI"
done

# Domain controllers expose current typed state and consumer subscriptions without CAN knowledge.
require_fixed "$GEAR" 'Subscription subscribe(Handler deliveryHandler, Listener listener)'
require_fixed "$GEAR" 'int currentGear()'
require_fixed "$DOOR" 'Subscription subscribe(Handler deliveryHandler, Listener listener)'
require_fixed "$DOOR" 'int currentFrontLeft()'
if grep -Eq 'CanBusEvent|CanBusEventHub|INTEREST_' "$GEAR" "$DOOR"; then
    fail "typed gear/door controllers depend on CAN transport"
fi

# Trips consume connection, gear and energy in one ordered hub mailbox so reconnects cannot
# overtake delayed gear callbacks. Driver-door edges still come from the typed controller.
require_fixed "$TRIPS" 'v.driverDoor().subscribe(timerHandler, this::onDoor)'
require_fixed "$TRIPS" 'CanBusEventRouter.INTEREST_CONNECTION | CanBusEventRouter.INTEREST_ENERGY_TELEMETRY | CanBusEventRouter.INTEREST_GEAR'
require_fixed "$TRIPS" 'null, timerHandler, this::onTelemetry);'
require_fixed "$TRIPS" 'if (e.kind == CanBusEvent.Kind.GEAR) { gearEventLive = e.origin == CanBusEvent.Origin.LIVE; onGear(e.first); }'
[ "$(grep -F -c 'CanBusEventHub.get(' "$TRIPS")" -eq 1 ] \
    || fail "TripStatsService must use the shared CanBusEventHub"
[ "$(grep -F -c 'hub.subscribe(' "$TRIPS")" -eq 1 ] \
    || fail "TripStatsService must have one ordered telemetry subscription"
if grep -Eq 'INTEREST_DOOR|INTEREST_VEHICLE_STATE|gear\(\)\.subscribe' "$TRIPS"; then
    fail "TripStatsService duplicates typed door/mode state or its ordered gear delivery"
fi

# Wipers and automatic light remain consumers of the typed gear/door controllers.
require_fixed "$WIPERS" 'vehicleState.gear().subscribe(timerHandler, this::onGearState)'
require_fixed "$WIPERS" 'vehicleState.driverDoor().subscribe('
require_fixed "$LIGHT" 'VehicleStateControllers.get(this).gear().subscribe('
if grep -Eq 'CanBusEvent|CanBusEventHub|INTEREST_' "$WIPERS"; then
    fail "WiperColdService directly depends on CAN transport"
fi
if grep -Fq 'CanBusEventRouter.INTEREST_GEAR' "$LIGHT"; then
    fail "LightSensorService still owns a direct gear CAN subscription"
fi

echo "PASS: typed vehicle controllers and ordered trip telemetry use the shared CAN hub"
