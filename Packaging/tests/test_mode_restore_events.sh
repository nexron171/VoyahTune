#!/bin/sh
set -eu
REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative"
ENGINE="$SRC/ApplyEngine.java"
# Automatic restore is owned by exactly the two vehicle events.
[ "$(grep -R 'ApplyEngine.scheduleApply(' "$SRC" | wc -l | tr -d ' ')" -eq 2 ]
grep -Fq 'ApplyEngine.scheduleApply("driver door opened")' "$SRC/VehicleStateControllers.java"
grep -Fq 'ApplyEngine.scheduleApply("gear Drive")' "$SRC/VehicleStateControllers.java"
# No delay/retry/coverage machinery remains in the apply engine or feedback policy.
if grep -E 'Thread.sleep|postDelayed|postAtTime|DEBOUNCE|WAKE_REPEAT|DEADLINE|SETTLE|CORRECT|coverage' \
    "$ENGINE" "$SRC/ModeSyncPolicy.java"; then
    echo "FAIL: timed/repeated restore remains" >&2
    exit 1
fi
grep -Fq 'int status = MainActivity.loadModes(ctx, true);' "$ENGINE"
grep -Fq 'result[0] = plan.sendPending(' "$ENGINE"
echo "PASS: independent door/Drive restores without delays or retries"
