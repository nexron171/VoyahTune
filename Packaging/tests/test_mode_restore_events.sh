#!/bin/sh
set -eu
REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative"
ENGINE="$SRC/ApplyEngine.java"
# Automatic restore is owned by door opening and its first subsequent Drive entry.
[ "$(grep -R 'ApplyEngine.scheduleApply(' "$SRC" | wc -l | tr -d ' ')" -eq 2 ]
grep -Fq 'ApplyEngine.scheduleApply("driver door opened")' "$SRC/VehicleStateControllers.java"
grep -Fq 'ApplyEngine.scheduleApply("gear Drive")' "$SRC/VehicleStateControllers.java"
# Connection changes must not erase the consumed Drive permission or door edge history.
if grep -Fq 'restoreTriggers.reset(' "$SRC/VehicleStateControllers.java"; then
    echo "FAIL: CAN reconnect resets the door/Drive restore history" >&2
    exit 1
fi
# No delay/retry/coverage machinery remains in the apply engine or feedback policy.
if grep -E 'Thread.sleep|postDelayed|postAtTime|DEBOUNCE|WAKE_REPEAT|DEADLINE|SETTLE|CORRECT|coverage' \
    "$ENGINE" "$SRC/ModeSyncPolicy.java"; then
    echo "FAIL: timed/repeated restore remains" >&2
    exit 1
fi
grep -Fq 'int status = MainActivity.loadModes(ctx, true);' "$ENGINE"
grep -Fq 'result[0] = plan.sendPending(' "$ENGINE"
echo "PASS: door/first-Drive restore wiring without reconnect resets, delays or retries"
