#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
APPLY_ENGINE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApplyEngine.java"

grep -Fq 'GENERATION_CHECK_INTERVAL_MS = 5_000L' "$APPLY_ENGINE" || {
    echo "FAIL: ApplyEngine generation check interval is not 5 seconds" >&2
    exit 1
}
grep -Fq 'Math.min(GENERATION_CHECK_INTERVAL_MS, deadline - now)' "$APPLY_ENGINE" || {
    echo "FAIL: cooperative wait does not use the 5-second interval" >&2
    exit 1
}
if grep -Fq 'Math.min(100L, deadline - now)' "$APPLY_ENGINE"; then
    echo "FAIL: old 100ms generation polling remains" >&2
    exit 1
fi

echo "PASS: ApplyEngine generation checks are spaced by up to 5 seconds"
