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

# One composition root owns the shared CAN subscription and routes typed domain state.
[ "$(grep -F -c 'CanBusEventHub.get(' "$COMPOSITION")" -eq 1 ] \
    || fail "VehicleStateControllers must have one CanBusEventHub acquisition"
require_fixed "$COMPOSITION" 'CanBusEventRouter.INTEREST_DOOR'
require_fixed "$COMPOSITION" 'CanBusEventRouter.INTEREST_GEAR'
require_fixed "$COMPOSITION" 'gearStateController.accept(event.first);'
require_fixed "$COMPOSITION" 'driverDoorStateController.accept('
require_fixed "$COMPOSITION" 'canBusEventHub.requestDriverDoorSeed();'

# Domain controllers expose current typed state and consumer subscriptions without CAN knowledge.
require_fixed "$GEAR" 'Subscription subscribe(Handler deliveryHandler, Listener listener)'
require_fixed "$GEAR" 'int currentGear()'
require_fixed "$DOOR" 'Subscription subscribe(Handler deliveryHandler, Listener listener)'
require_fixed "$DOOR" 'int currentFrontLeft()'
if grep -Eq 'CanBusEvent|CanBusEventHub|INTEREST_' "$GEAR" "$DOOR"; then
    fail "typed gear/door controllers depend on CAN transport"
fi

# Consumers depend only on typed controllers. None owns a door/gear CAN subscription anymore.
require_fixed "$TRIPS" 'vehicleState.gear().subscribe(timerHandler, this::onGear)'
require_fixed "$TRIPS" 'vehicleState.driverDoor().subscribe(timerHandler, state -> {'
require_fixed "$WIPERS" 'vehicleState.gear().subscribe(timerHandler, this::onGearState)'
require_fixed "$WIPERS" 'vehicleState.driverDoor().subscribe('
require_fixed "$LIGHT" 'VehicleStateControllers.get(this).gear().subscribe('
for consumer in "$TRIPS" "$WIPERS"; do
    if grep -Eq 'CanBusEvent|CanBusEventHub|INTEREST_' "$consumer"; then
        fail "$consumer directly depends on CAN transport"
    fi
done
if grep -Fq 'CanBusEventRouter.INTEREST_GEAR' "$LIGHT"; then
    fail "LightSensorService still owns a direct gear CAN subscription"
fi

echo "PASS: gear and driver-door state are centralized behind typed controllers"
