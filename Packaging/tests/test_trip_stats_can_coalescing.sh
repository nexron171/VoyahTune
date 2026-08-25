#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/TripStatsService.java"
MODE_POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeSyncPolicy.java"
APPLY_ENGINE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApplyEngine.java"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# Every Gear transition still reaches the in-memory state machine. Only the expensive disk/global
# publication is delayed and coalesced; this is not a poll and it never self-rearms.
require_fixed "$SERVICE" 'CAN_STATE_PUBLISH_COALESCE_MS = 250L'
require_fixed "$SERVICE" 'private void onGear(int gearVal)'
require_fixed "$SERVICE" 'scheduleCanStatePublish();'
require_fixed "$SERVICE" \
    'timerHandler.postDelayed(canStatePublishRunnable, CAN_STATE_PUBLISH_COALESCE_MS);'
require_fixed "$SERVICE" 'private void persistState()'
require_fixed "$SERVICE" 'if (canStatePublishPending) {'

ON_GEAR=$(awk '
    /private void onGear\(int gearVal\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SERVICE")
printf '%s\n' "$ON_GEAR" | grep -Fq 'scheduleCanStatePublish();' \
    || fail "Gear callback must schedule one coalesced latest-state publication"
if printf '%s\n' "$ON_GEAR" | grep -Eq 'prefs\(\)|sendBroadcast|persistAndBroadcast'; then
    fail "Gear callback must not write preferences or broadcast inline"
fi

[ "$(grep -F -c 'postDelayed(canStatePublishRunnable, CAN_STATE_PUBLISH_COALESCE_MS)' \
        "$SERVICE")" -eq 1 ] \
    || fail "Trip state publisher must have exactly one delayed scheduling site"
if grep -Fq 'postDelayed(this' "$SERVICE"; then
    fail "TripStatsService must not create a self-rearming poll"
fi

# OEM Eco/EV published during wake is feedback, but must not overwrite the restored source of truth.
# After the finite 30-second guard the car is again allowed to become the source of truth.
require_fixed "$MODE_POLICY" 'POST_RESTORE_SETTLE_MS = 30_000L'
require_fixed "$MODE_POLICY" \
    'if (restoreCompleted && nowUptime >= acceptAfterUptime) return Decision.ACCEPT;'
require_fixed "$APPLY_ENGINE" 'MODE_SYNC_POLICY.canPersist('
require_fixed "$SERVICE" \
    'ApplyEngine.persistModeFeedbackIfAllowed(getApplicationContext(), energy, mode);'

echo "PASS: TripStats preserves Gear transitions and coalesces CAN-driven disk/broadcast work"
