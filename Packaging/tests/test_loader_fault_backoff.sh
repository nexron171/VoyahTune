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
        'LOAD_LOCK=/data/local/tmp/voyahtune_load.v2.lock' \
        'VDMARK=/data/local/tmp/voyahtune_vd.pid' \
        'SWK_KM_MARK=/data/local/tmp/voyahtune_swk_km.pid' \
        'SWK_KM_BUSY=/data/local/tmp/voyahtune_swk_km.busy' \
        'LNCH_MARK=/data/local/tmp/voyahtune_lnch.pid' \
        'MD_MARK=/data/local/tmp/voyahtune_md.pid' \
        'VD_ATTEMPT=/data/local/tmp/voyahtune_vd.attempt' \
        'SWK_KM_ATTEMPT=/data/local/tmp/voyahtune_swk_km.attempt' \
        'LNCH_ATTEMPT=/data/local/tmp/voyahtune_lnch.attempt' \
        'MD_ATTEMPT=/data/local/tmp/voyahtune_md.attempt' \
        'other_loader_pid_is_live "$LOAD_LOCK_OWNER" && exit 0' \
        'LOAD_LOCK_MAX_ATTEMPTS=30' \
        'owner lock unavailable after ${LOAD_LOCK_ATTEMPTS}s; stop without spinning' \
        'echo "v2:$ID_BOOT:$ID_PID:$ID_START"' \
        'reserve_injection_attempt "$6" "$5"' \
        'CURRENT_ID=$(process_identity "$1" "$7"' \
        'printf '\''%s\n'\'' "$INJECTED_ID" > "$MARK_TMP" && mv -f "$MARK_TMP" "$4"' \
        'latched for this process; no retry' \
        'inject_keymanager_bg "$KMP" "$KMP_ID"' \
        'latched; no retry'; do
    require_fixed "$LOAD_BIN" "$REQUIRED"
done

for FORBIDDEN in \
        'INJECT_RETRY_INITIAL' \
        'INJECT_RETRY_MAX' \
        'injection_retry_due' \
        'record_injection_failure' \
        'record_injection_success' \
        'retry next loop' \
        'bounded backoff' \
        '60s backoff'; do
    if grep -Fq "$FORBIDDEN" "$LOAD_BIN"; then
        fail "same-process recurring retry remains: $FORBIDDEN"
    fi
done

if grep -Fq 'rm -f "$5"' "$LOAD_BIN" || grep -Fq 'rm -f "$SWK_KM_ATTEMPT"' "$LOAD_BIN"; then
    fail "successful injection clears its one-shot latch and can be repeated after marker loss"
fi

# The persistent attempt reservation must precede every frida-inject execution.
inject_function=$(awk '
    /^inject_ret\(\) \{/ { capture = 1 }
    /^# VehicleSetting/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$inject_function" ] || fail "cannot extract returning injector"
reserve_line=$(printf '%s\n' "$inject_function" | grep -nF 'reserve_injection_attempt "$6" "$5"' | cut -d: -f1)
frida_line=$(printf '%s\n' "$inject_function" | grep -nF 'timeout -k 5 30 "$FI"' | cut -d: -f1)
[ "$reserve_line" -lt "$frida_line" ] || fail "returning injector reserves after frida-inject"

apollo_function=$(awk '
    /^inject_apollo\(\) \{/ { capture = 1 }
    /^# Монотонные секунды/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$apollo_function" ] || fail "cannot extract Apollo injector"
apollo_reserve_line=$(printf '%s\n' "$apollo_function" \
    | grep -nF 'reserve_injection_attempt "$APOLLO_ID" "$APOLLO_ATTEMPT"' | cut -d: -f1)
apollo_frida_line=$(printf '%s\n' "$apollo_function" \
    | grep -nF 'timeout -k 5 30 "$FI"' | cut -d: -f1)
[ "$apollo_reserve_line" -lt "$apollo_frida_line" ] \
    || fail "Apollo injector reserves after frida-inject"

km_function=$(awk '
    /^inject_keymanager_bg\(\) \{/ { capture = 1 }
    /^watchdog_cycle\(\) \{/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$km_function" ] || fail "cannot extract keymanager injector"
km_reserve_line=$(printf '%s\n' "$km_function" | grep -nF 'reserve_injection_attempt "$KM_TARGET_ID"' | cut -d: -f1)
km_frida_line=$(printf '%s\n' "$km_function" | grep -nF 'timeout -k 5 30 "$FI"' | cut -d: -f1)
[ "$km_reserve_line" -lt "$km_frida_line" ] || fail "keymanager reserves after background frida-inject"

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

echo "PASS: loader allows at most one Frida attempt per exact process identity"
