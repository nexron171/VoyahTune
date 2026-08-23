#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/apollo-loader-one-shot.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' 0 HUP INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

extract_function() {
    awk -v signature="$1() {" '
        $0 == signature { capture = 1 }
        capture { print }
        capture && /^}$/ { found = 1; exit }
        END { if (!found) exit 1 }
    ' "$LOAD_BIN"
}

FUNCTIONS="$TEST_ROOT/functions.sh"
for FUNCTION in reset_apollo_liveness verify_apollo_liveness_reset disable_apollo_legacy_hook \
        apollo_startup_once watchdog_cycle; do
    extract_function "$FUNCTION" >> "$FUNCTIONS" \
        || fail "cannot extract $FUNCTION from load.bin"
done
# shellcheck disable=SC1090
. "$FUNCTIONS"

EVENTS="$TEST_ROOT/events"
TARGET_ALIVE="$TEST_ROOT/target.alive"
TARGET_ID="$TEST_ROOT/target.identity"
IDENTITY_CALLS="$TEST_ROOT/identity.calls"
PUT_RESTARTED="$TEST_ROOT/put-restarted"

APOLLO_TARGET=com.qinggan.app.vehiclesetting
APOLLO_LEGACY_OPT_IN_KEY=open_voyah_apollo_legacy_hook_enabled
APOLLO_MARK="$TEST_ROOT/voyah_apollo.pid"
APOLLO_DOWN_MARK="$TEST_ROOT/voyah_apollo.down"
APOLLO_DISABLED_MARK="$TEST_ROOT/voyah_apollo.disabled"
APOLLO="$TEST_ROOT/apollo_tech.js"
APOLLO_LOG="$TEST_ROOT/voyah_apollo.txt"
APOLLO_READY_MARKER='[apollo] hook ready'
VD="$TEST_ROOT/vd_bypass.js"
SWK="$TEST_ROOT/steeringwheelkeys.js"
LNCH="$TEST_ROOT/launcherdock.js"
MD="$TEST_ROOT/multidisplay.js"
VDMARK="$TEST_ROOT/voyah_vd.pid"
SWK_KM_MARK="$TEST_ROOT/voyah_swk_km.pid"
LNCH_MARK="$TEST_ROOT/voyah_lnch.pid"
MD_MARK="$TEST_ROOT/voyah_md.pid"
MOCK_PID=4242

apollo_settings_get() {
    echo "get:$1" >> "$EVENTS"
    if [ "$1" = "$APOLLO_LEGACY_OPT_IN_KEY" ]; then
        [ "$MOCK_OPT_IN_GET_FAIL" -eq 0 ] || return 1
        printf '%s\n' "$MOCK_OPT_IN"
        return
    fi
    case "|$MOCK_READBACK_FAILURES|" in
        *"|$1|"*) return 1 ;;
    esac
    case "|$MOCK_READBACK_NONZERO|" in
        *"|$1|"*) echo 1 ;;
        *) echo 0 ;;
    esac
}

apollo_settings_put_zero() {
    echo "put:$1" >> "$EVENTS"
    if [ "$MOCK_RESTART_DURING_PUT" -eq 1 ] && [ ! -f "$PUT_RESTARTED" ]; then
        : > "$PUT_RESTARTED"
        echo 1 > "$TARGET_ALIVE"
        echo "$MOCK_REPLACEMENT_ID" > "$TARGET_ID"
    fi
    case "|$MOCK_PUT_FAILURES|" in
        *"|$1|"*) return 1 ;;
    esac
    return 0
}

apollo_force_stop_target() {
    echo "stop:$APOLLO_TARGET" >> "$EVENTS"
    if [ "$MOCK_STOP_LEAVES_ALIVE" -eq 0 ]; then
        echo 0 > "$TARGET_ALIVE"
    fi
    [ "$MOCK_STOP_STATUS" -eq 0 ]
}

pidof() {
    if [ "$1" = "$APOLLO_TARGET" ]; then
        echo "pidof:$1" >> "$EVENTS"
        [ "$(cat "$TARGET_ALIVE")" -eq 1 ] && printf '%s\n' "$MOCK_PID"
    fi
}

target_identity() {
    [ "$1" = "$MOCK_PID" ] || return 1
    [ "$2" = "$APOLLO_TARGET" ] || return 1
    IDENTITY_CALL_COUNT=$(cat "$IDENTITY_CALLS")
    IDENTITY_CALL_COUNT=$((IDENTITY_CALL_COUNT + 1))
    echo "$IDENTITY_CALL_COUNT" > "$IDENTITY_CALLS"
    if [ "$MOCK_FLIP_ON_ID_CALL" -eq "$IDENTITY_CALL_COUNT" ]; then
        echo "$MOCK_REPLACEMENT_ID" > "$TARGET_ID"
    fi
    [ "$(cat "$TARGET_ALIVE")" -eq 1 ] || return 1
    CURRENT_ID=$(cat "$TARGET_ID")
    [ -n "$CURRENT_ID" ] || return 1
    printf '%s\n' "$CURRENT_ID"
}

kill() {
    [ "$1" = "-0" ] || return 1
    [ "$2" = "$MOCK_PID" ] || return 1
    [ "$(cat "$TARGET_ALIVE")" -eq 1 ]
}

inject_verified_marker() {
    echo "attach:$1:$2" >> "$EVENTS"
    [ "$MOCK_ATTACH_STATUS" -eq 0 ] || return 1
    target_identity "$1" "$2" > "$5" || return 1
    return 0
}

inject_ret() {
    fail "watchdog unexpectedly reached inject_ret"
}

inject_keymanager_bg() {
    fail "watchdog unexpectedly reached inject_keymanager_bg"
}

logi() {
    echo "logi:$1" >> "$EVENTS"
}

loge() {
    echo "loge:$1" >> "$EVENTS"
}

reset_scenario() {
    : > "$EVENTS"
    rm -f "$APOLLO_MARK" "$APOLLO_DOWN_MARK" "$APOLLO_DISABLED_MARK" \
        "$APOLLO_LOG" "$PUT_RESTARTED"
    : > "$APOLLO"
    echo 0 > "$TARGET_ALIVE"
    : > "$TARGET_ID"
    echo 0 > "$IDENTITY_CALLS"
    MOCK_OPT_IN=0
    MOCK_OPT_IN_GET_FAIL=0
    MOCK_PUT_FAILURES=
    MOCK_READBACK_FAILURES=
    MOCK_READBACK_NONZERO=
    MOCK_STOP_LEAVES_ALIVE=0
    MOCK_STOP_STATUS=0
    MOCK_ATTACH_STATUS=0
    MOCK_FLIP_ON_ID_CALL=0
    MOCK_RESTART_DURING_PUT=0
    MOCK_REPLACEMENT_ID='v2|boot|4242|new'
}

count_event() {
    awk -v prefix="$1" 'index($0, prefix) == 1 { count++ } END { print count + 0 }' "$EVENTS"
}

assert_event_count() {
    ACTUAL=$(count_event "$1")
    [ "$ACTUAL" -eq "$2" ] || fail "$3: expected $2 '$1' events, got $ACTUAL"
}

assert_file_exists() {
    [ -f "$1" ] || fail "$2: expected $1"
}

assert_file_absent() {
    [ ! -e "$1" ] || fail "$2: unexpected $1"
}

run_watchdog_10000() {
    ITERATION=0
    while [ "$ITERATION" -lt 10000 ]; do
        watchdog_cycle
        ITERATION=$((ITERATION + 1))
    done
}

# The production loader intentionally has no `set -e`: missing marker reads and failed bounded
# operations are data for its fail-closed branches. Keep nounset protection, but do not let the host
# harness terminate before those branches can be asserted below.
set +e

# Normal direct-only boot performs exactly one opt-in get, three independent puts/readbacks, and
# exact-owner stop. Ten thousand permanent-loop cycles cannot add any Apollo call or log.
reset_scenario
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|old' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'get:' 4 'disabled startup'
assert_event_count 'get:open_voyah_apollo_legacy_hook_enabled' 1 'disabled startup opt-in'
assert_event_count 'put:' 3 'disabled startup'
assert_event_count 'stop:' 1 'disabled startup'
assert_event_count 'attach:' 0 'disabled startup'
assert_file_absent "$APOLLO_MARK" 'disabled startup'
assert_file_exists "$APOLLO_DISABLED_MARK" 'disabled startup'
EVENT_COUNT_BEFORE=$(wc -l < "$EVENTS")
run_watchdog_10000
EVENT_COUNT_AFTER=$(wc -l < "$EVENTS")
[ "$EVENT_COUNT_AFTER" -eq "$EVENT_COUNT_BEFORE" ] \
    || fail "10k watchdog cycles added Apollo/PID/marker/attach/log activity"
assert_event_count 'get:' 4 'disabled startup after watchdog'
assert_event_count 'get:open_voyah_apollo_legacy_hook_enabled' 1 \
    'disabled startup opt-in after watchdog'

# Explicit startup opt-in makes one verified attach to an already-running exact target and never
# retries it from the permanent watchdog.
reset_scenario
MOCK_OPT_IN=1
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|fresh' > "$TARGET_ID"
apollo_startup_once
assert_event_count 'get:' 3 'enabled startup'
assert_event_count 'get:open_voyah_apollo_legacy_hook_enabled' 1 'enabled startup opt-in'
assert_event_count 'put:' 2 'enabled startup'
assert_event_count 'pidof:' 1 'enabled startup'
assert_event_count 'attach:' 1 'enabled startup'
assert_file_exists "$APOLLO_MARK" 'enabled startup'
EVENT_COUNT_BEFORE=$(wc -l < "$EVENTS")
watchdog_cycle
EVENT_COUNT_AFTER=$(wc -l < "$EVENTS")
[ "$EVENT_COUNT_AFTER" -eq "$EVENT_COUNT_BEFORE" ] \
    || fail "enabled startup was retried from watchdog"

# Missing target is fail-passive and receives no retry until an explicit loader restart.
reset_scenario
MOCK_OPT_IN=1
apollo_startup_once
assert_event_count 'get:' 3 'missing target'
assert_event_count 'get:open_voyah_apollo_legacy_hook_enabled' 1 'missing target opt-in'
assert_event_count 'put:' 2 'missing target'
assert_event_count 'pidof:' 1 'missing target'
assert_event_count 'attach:' 0 'missing target'
EVENT_COUNT_BEFORE=$(wc -l < "$EVENTS")
watchdog_cycle
EVENT_COUNT_AFTER=$(wc -l < "$EVENTS")
[ "$EVENT_COUNT_AFTER" -eq "$EVENT_COUNT_BEFORE" ] \
    || fail "missing target received an automatic retry"

# False-success in an active non-exact liveness put is caught by the two bounded verification reads;
# attach is forbidden and no ready marker is published.
reset_scenario
MOCK_OPT_IN=1
MOCK_READBACK_NONZERO=open_voyah_apollo_profile_supported
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|fresh' > "$TARGET_ID"
apollo_startup_once
assert_event_count 'get:' 3 'active liveness verification failure'
assert_event_count 'put:' 2 'active liveness verification failure'
assert_event_count 'attach:' 0 'active liveness verification failure'
assert_file_absent "$APOLLO_MARK" 'active liveness verification failure'

# Restarting only the loader while its exact agent is already alive is a strict no-op after the
# startup opt-in read: no liveness zeroes and no duplicate attach.
reset_scenario
MOCK_OPT_IN=1
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|existing' > "$TARGET_ID"
echo 'v2|boot|4242|existing' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'get:' 1 'exact already-attached target'
assert_event_count 'put:' 0 'exact already-attached target'
assert_event_count 'attach:' 0 'exact already-attached target'
[ "$(cat "$APOLLO_MARK")" = 'v2|boot|4242|existing' ] \
    || fail "exact already-attached marker changed"

# A failed/null startup opt-in read is the disabled branch; the three remaining reads only verify
# the one-shot cleanup.
reset_scenario
MOCK_OPT_IN_GET_FAIL=1
apollo_startup_once
assert_event_count 'get:' 4 'failed opt-in read'
assert_event_count 'get:open_voyah_apollo_legacy_hook_enabled' 1 'failed opt-in read'
assert_event_count 'put:' 3 'failed opt-in read'
assert_event_count 'attach:' 0 'failed opt-in read'
assert_file_exists "$APOLLO_DISABLED_MARK" 'failed opt-in read'

# Settings failures cannot skip exact-owned stop. Cleanup remains visibly incomplete without a
# disabled marker, but a successfully stopped identity marker is removed.
reset_scenario
MOCK_PUT_FAILURES=open_voyah_apollo_master
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|old' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 1 'settings failure exact owner'
assert_event_count 'put:' 3 'settings failure exact owner'
assert_file_absent "$APOLLO_MARK" 'settings failure exact owner'
assert_file_absent "$APOLLO_DISABLED_MARK" 'settings failure exact owner'

# SettingsCmd may falsely return success after a RemoteException. A nonzero bounded readback must
# therefore prevent publication of the disabled marker even when all three put statuses were zero.
reset_scenario
MOCK_READBACK_NONZERO=open_voyah_apollo_profile_supported
apollo_startup_once
assert_event_count 'get:' 4 'false-success put verification'
assert_event_count 'put:' 3 'false-success put verification'
assert_file_absent "$APOLLO_DISABLED_MARK" 'false-success put verification'

# A stop that leaves the saved exact identity alive keeps its proof and fails closed.
reset_scenario
MOCK_STOP_LEAVES_ALIVE=1
MOCK_STOP_STATUS=1
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|old' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 1 'failed force-stop'
assert_file_exists "$APOLLO_MARK" 'failed force-stop'
assert_file_absent "$APOLLO_DISABLED_MARK" 'failed force-stop'

# A stale/mismatched marker never authorizes package stop and is removed after successful puts.
reset_scenario
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|new' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 0 'mismatched marker'
assert_file_absent "$APOLLO_MARK" 'mismatched marker'
assert_file_exists "$APOLLO_DISABLED_MARK" 'mismatched marker'

# Unreadable identity is unresolved: never kill it, never erase its marker, never claim cleanup.
reset_scenario
echo 1 > "$TARGET_ALIVE"
: > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 0 'unreadable identity'
assert_file_exists "$APOLLO_MARK" 'unreadable identity'
assert_file_absent "$APOLLO_DISABLED_MARK" 'unreadable identity'

# TOCTOU fence: if identity changes between the ownership snapshot and immediate pre-stop check,
# the replacement is never force-stopped and the now-stale marker is removable.
reset_scenario
MOCK_FLIP_ON_ID_CALL=2
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|old' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 0 'pre-stop identity replacement'
assert_file_absent "$APOLLO_MARK" 'pre-stop identity replacement'
assert_file_exists "$APOLLO_DISABLED_MARK" 'pre-stop identity replacement'

# A replacement started by the first Settings put appears only after the authorized exact stop;
# no later cleanup step can reuse the old proof or stop the fresh process.
reset_scenario
MOCK_RESTART_DURING_PUT=1
echo 1 > "$TARGET_ALIVE"
echo 'v2|boot|4242|old' > "$TARGET_ID"
echo 'v2|boot|4242|old' > "$APOLLO_MARK"
apollo_startup_once
assert_event_count 'stop:' 1 'replacement during settings cleanup'
assert_event_count 'put:' 3 'replacement during settings cleanup'
[ "$(cat "$TARGET_ALIVE")" -eq 1 ] || fail "fresh replacement was stopped after Settings cleanup"
[ "$(cat "$TARGET_ID")" = "$MOCK_REPLACEMENT_ID" ] \
    || fail "fresh replacement identity was not preserved"
STOP_LINE=$(grep -n '^stop:' "$EVENTS" | sed -n '1s/:.*//p')
PUT_LINE=$(grep -n '^put:' "$EVENTS" | sed -n '1s/:.*//p')
[ "$STOP_LINE" -lt "$PUT_LINE" ] || fail "exact stop must happen before bounded Settings writes"

echo "PASS: Apollo loader startup is one-shot and permanent watchdog is Apollo-free"
