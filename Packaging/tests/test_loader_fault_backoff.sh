#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

for REQUIRED in \
        'LOAD_LOCK_MAX_ATTEMPTS=30' \
        'owner lock unavailable after ${LOAD_LOCK_ATTEMPTS}s; stop without spinning' \
        'sleep 1' \
        'INJECT_RETRY_INITIAL=10' \
        'INJECT_RETRY_MAX=60' \
        'record_injection_failure "$5" "$1"' \
        'injection_retry_due vd "$SS"' \
        'injection_retry_due launcher "$LP"' \
        'injection_retry_due multidisplay "$MDP"' \
        'KM_BUSY_MAX_ATTEMPTS=3' \
        'KM_RETRY_AT=$((KM_NOW + SWK_KM_RETRY_DELAY))'; do
    require_fixed "$LOAD_BIN" "$REQUIRED"
done

RETRY_FUNCTIONS=$(awk '
    /^retry_state\(\) \{/ { capture = 1 }
    /^# `timeout` uses a clock/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$RETRY_FUNCTIONS" ] || fail "cannot extract retry helpers"

INJECT_RETRY_INITIAL=10
INJECT_RETRY_MAX=60
VD_RETRY_PID= VD_RETRY_AT=0 VD_RETRY_DELAY=$INJECT_RETRY_INITIAL
LNCH_RETRY_PID= LNCH_RETRY_AT=0 LNCH_RETRY_DELAY=$INJECT_RETRY_INITIAL
MD_RETRY_PID= MD_RETRY_AT=0 MD_RETRY_DELAY=$INJECT_RETRY_INITIAL
TEST_NOW=100
uptime_seconds() { echo "$TEST_NOW"; }
eval "$RETRY_FUNCTIONS"

injection_retry_due vd 101 || fail "new PID must be due immediately"
record_injection_failure vd 101
[ "$VD_RETRY_AT" -eq 110 ] || fail "first retry deadline must be +10s"
[ "$VD_RETRY_DELAY" -eq 20 ] || fail "first retry must advance delay to 20s"
TEST_NOW=109
if injection_retry_due vd 101; then
    fail "same PID must remain suppressed before its deadline"
fi
TEST_NOW=110
injection_retry_due vd 101 || fail "same PID must become due at deadline"
record_injection_failure vd 101
[ "$VD_RETRY_AT" -eq 130 ] || fail "second retry deadline must be +20s"
[ "$VD_RETRY_DELAY" -eq 40 ] || fail "second retry must advance delay to 40s"
TEST_NOW=130
record_injection_failure vd 101
TEST_NOW=170
record_injection_failure vd 101
[ "$VD_RETRY_DELAY" -eq 60 ] || fail "retry delay must cap at 60s"

TEST_NOW=171
injection_retry_due vd 202 || fail "PID replacement must reset backoff immediately"
[ "$VD_RETRY_PID" = 202 ] || fail "PID replacement must become the tracked identity"
[ "$VD_RETRY_AT" -eq 0 ] || fail "PID replacement must clear the deadline"
[ "$VD_RETRY_DELAY" -eq 10 ] || fail "PID replacement must restore initial delay"

record_injection_success vd 202
[ "$VD_RETRY_AT" -eq 0 ] || fail "success must clear retry deadline"
[ "$VD_RETRY_DELAY" -eq 10 ] || fail "success must restore initial delay"

echo "PASS: loader fault loops are bounded and same-PID injection failures back off"
