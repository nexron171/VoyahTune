#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/LightSensorService.java"
ROUTER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/CanBusEventRouter.java"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# Preserve the proven auto-light behaviour: callbacks remain the primary source, while one
# independent 30-second watchdog recovers the target after missed OEM/manual Auto transitions.
require_fixed "$SERVICE" 'SAFETY_POLL_MS = 30_000L'
require_fixed "$SERVICE" 'timerHandler.postDelayed(safetyRunnable, 2_000L);'
require_fixed "$SERVICE" 'timerHandler.postDelayed(this, SAFETY_POLL_MS);'
require_fixed "$SERVICE" 'timerHandler.removeCallbacks(safetyRunnable);'
[ "$(grep -F -c 'timerHandler.postDelayed(this, SAFETY_POLL_MS);' "$SERVICE")" -eq 1 ] \
    || fail "safety watchdog must have exactly one self-rearm site"
require_fixed "$SERVICE" 'if (!everSent && outdoor != null)'
require_fixed "$SERVICE" 'applyTargetWithSensorLevel("poll-retry", -1);'
require_fixed "$SERVICE" 'requestSensorLevel(epoch);'

# An incoming CAN callback may update state or schedule one bounded debounce, but it must never
# start/rearm the watchdog. Thus callback storms cannot multiply periodic CAN work.
CAN_EVENT_BLOCK=$(awk '
    /private void onCanBusEvent\(CanBusEvent event\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SERVICE")
if printf '%s\n' "$CAN_EVENT_BLOCK" | grep -Fq 'safetyRunnable'; then
    fail "incoming CAN events must not schedule the safety watchdog"
fi

# Keep legacy target recovery semantics.
require_fixed "$SERVICE" 'timerHandler.postDelayed(driveFallbackRunnable, DRIVE_FALLBACK_MS);'
require_fixed "$SERVICE" 'timerHandler.postDelayed(canbusReassertRunnable, CANBUS_REASSERT_DELAY_MS);'
require_fixed "$SERVICE" 'if (autoLamp == lastAutoLamp && dippedBeam == lastDippedBeam && headLight == lastHeadLight)'
require_fixed "$SERVICE" 'if (since < HEADLIGHT_GUARD_MS)'
require_fixed "$SERVICE" 'if (!everSent || desired != headlightsOn) commit(desired, "ext-sensor reason=" + reason);'
require_fixed "$SERVICE" 'if (MANUAL_AUTO_GATE.blocksAntiAuto())'
require_fixed "$SERVICE" 'force-init'

# CAN fan-out remains bounded and coalesces repeated level events before the service main looper.
require_fixed "$ROUTER" 'DEFAULT_MAILBOX_CAPACITY = 32'
require_fixed "$ROUTER" 'if (event.samePayload(previous))'
require_fixed "$ROUTER" 'if (!event.isOrderedTransition()) removeQueuedLevel(key);'
require_fixed "$ROUTER" 'if (queue.size() == capacity)'
require_fixed "$ROUTER" 'DRAIN_SLICE = 1'

echo "PASS: legacy auto-light behaviour restored with bounded CAN ingress"
