#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LOAD_BIN="$REPO_ROOT/Packaging/od/system/load.bin"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# Execute the real reservation helper: same identity is permanently rejected; a new exact identity
# replaces the latch and is allowed once.
reserve_function=$(awk '
    /^reserve_injection_attempt\(\) \{/ { capture = 1 }
    /^#   \$1=pid/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$reserve_function" ] || fail "cannot extract attempt reservation"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
ATTEMPT_FILE="$TMP_DIR/hook.attempt"
loge() { :; }
eval "$reserve_function"

FIRST_ID='v2:boot-a:101:500'
REPLACED_SAME_PID_ID='v2:boot-a:101:900'
REBOOTED_SAME_PID_ID='v2:boot-b:101:500'

reserve_injection_attempt "$FIRST_ID" "$ATTEMPT_FILE" \
    || fail "first exact identity must be allowed"
[ "$(cat "$ATTEMPT_FILE")" = "$FIRST_ID" ] || fail "first identity was not persisted"
if reserve_injection_attempt "$FIRST_ID" "$ATTEMPT_FILE"; then
    fail "same exact process identity received a second attempt"
fi
reserve_injection_attempt "$REPLACED_SAME_PID_ID" "$ATTEMPT_FILE" \
    || fail "same PID with a new start time must be allowed once"
if reserve_injection_attempt "$REPLACED_SAME_PID_ID" "$ATTEMPT_FILE"; then
    fail "replacement identity received a second attempt"
fi
reserve_injection_attempt "$REBOOTED_SAME_PID_ID" "$ATTEMPT_FILE" \
    || fail "same PID after reboot must be a new exact identity"

# Execute the real multidisplay retry record helpers with a deterministic monotonic clock.
md_retry_functions=$(awk '
    /^read_md_attempt_record\(\) \{/ { capture = 1 }
    /^# Exact-ready multidisplay injector/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$md_retry_functions" ] || fail "cannot extract multidisplay retry helpers"
eval "$md_retry_functions"

MD_MAX_ATTEMPTS=3
MD_RETRY_CORE_SECONDS=2
MD_RETRY_SHORT_SECONDS=20
MD_RETRY_LONG_SECONDS=60
TEST_UPTIME=100
uptime_seconds() { echo "$TEST_UPTIME"; }
MD_ATTEMPT_FILE="$TMP_DIR/md.attempt"
MD_ID='v2:boot-a:202:700'
MD_NEW_ID='v2:boot-a:202:900'

reserve_md_injection_attempt "$MD_ID" "$MD_ATTEMPT_FILE" \
    || fail "multidisplay first attempt must be allowed"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_ID|1|120" ] \
    || fail "multidisplay first retry deadline mismatch"
if reserve_md_injection_attempt "$MD_ID" "$MD_ATTEMPT_FILE"; then
    fail "multidisplay retried before short backoff"
fi
TEST_UPTIME=105
record_md_injection_failure "$MD_ID" "$MD_ATTEMPT_FILE" 1 agent_core_failure \
    || fail "multidisplay failure record update failed"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_ID|1|107" ] \
    || fail "multidisplay core failure did not rebase the 2s bootstrap retry"
TEST_UPTIME=107
reserve_md_injection_attempt "$MD_ID" "$MD_ATTEMPT_FILE" \
    || fail "multidisplay second attempt was not released after backoff"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_ID|2|167" ] \
    || fail "multidisplay long retry deadline mismatch"
TEST_UPTIME=167
reserve_md_injection_attempt "$MD_ID" "$MD_ATTEMPT_FILE" \
    || fail "multidisplay third attempt was not released after backoff"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_ID|3|167" ] \
    || fail "multidisplay terminal attempt record mismatch"
if reserve_md_injection_attempt "$MD_ID" "$MD_ATTEMPT_FILE"; then
    fail "multidisplay exceeded its persistent three-attempt cap"
fi
reserve_md_injection_attempt "$MD_NEW_ID" "$MD_ATTEMPT_FILE" \
    || fail "replacement systemservice identity must reset multidisplay retry budget"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_NEW_ID|1|187" ] \
    || fail "replacement identity did not receive a fresh bounded budget"
record_md_injection_success "$MD_NEW_ID" "$MD_ATTEMPT_FILE" \
    || fail "multidisplay success terminal latch failed"
[ "$(cat "$MD_ATTEMPT_FILE")" = "$MD_NEW_ID|3|0" ] \
    || fail "multidisplay success does not prevent marker-loss reinjection"
MD_SUCCESS_MARK="$TMP_DIR/md.pid"
load_md_runtime_hook_state "$MD_NEW_ID" "$MD_SUCCESS_MARK" "$MD_ATTEMPT_FILE"
[ "$RUNTIME_STATE" = failed ] \
    || fail "terminal retry latch without exact ready marker was reported active"
printf '%s\n' "$MD_NEW_ID" > "$MD_SUCCESS_MARK"
load_md_runtime_hook_state "$MD_NEW_ID" "$MD_SUCCESS_MARK" "$MD_ATTEMPT_FILE"
[ "$RUNTIME_STATE" = active ] \
    || fail "exact multidisplay success marker does not dominate terminal attempt latch"

# Missing stdout is also safe for the fast path: an installed core has the JVM sentinel, while an
# agent that never ran still remains inside the same hard three-attempt budget.
MD_MISSING_ID='v2:boot-a:303:1000'
MD_MISSING_FILE="$TMP_DIR/md-missing.attempt"
TEST_UPTIME=300
reserve_md_injection_attempt "$MD_MISSING_ID" "$MD_MISSING_FILE" \
    || fail "missing-marker scenario could not reserve first attempt"
TEST_UPTIME=305
record_md_injection_failure "$MD_MISSING_ID" "$MD_MISSING_FILE" 1 ready_marker_missing \
    || fail "missing-marker quick retry record failed"
[ "$(cat "$MD_MISSING_FILE")" = "$MD_MISSING_ID|1|307" ] \
    || fail "missing ready marker did not receive the sentinel-safe 2s retry"

echo "PASS: generic hooks are one-shot; multidisplay alone has exact-ready bounded retry"
