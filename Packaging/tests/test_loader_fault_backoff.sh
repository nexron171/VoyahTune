#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
MULTIDISPLAY="$REPO_ROOT/Packaging/inject/multidisplay.js"
LOAD_RC="$REPO_ROOT/Packaging/system/voyahtune.load.rc"
FULL_INSTALL="$REPO_ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$REPO_ROOT/Packaging/installer/full/install.bat"

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

# The server whitelist hook is a deliberately narrow exception to generic one-shot injection. It
# must prove agent readiness, persist a hard cap, and never infer success from frida-inject rc=0.
for REQUIRED in \
        "MD_READY_MARKER='[multidisplay] hook ready v2'" \
        "MD_FAILURE_MARKER='[multidisplay] hook failed v2'" \
        'MD_MAX_ATTEMPTS=3' \
        'MD_RETRY_CORE_SECONDS=2' \
        'MD_RETRY_SHORT_SECONDS=20' \
        'MD_RETRY_LONG_SECONDS=60' \
        'MD_BOOTSTRAP_WAIT_SECONDS=15' \
        'MD_PRIORITY_DEFER_CYCLES=1' \
        'reserve_md_injection_attempt "$MD_INJECT_ID" "$MD_ATTEMPT"' \
        'grep -qF "$MD_FAILURE_MARKER " "$MD_TRY"' \
        'grep -qF "$MD_READY_MARKER " "$MD_TRY"' \
        'record_md_injection_success "$MD_INJECT_ID" "$MD_ATTEMPT"' \
        'load_md_runtime_hook_state "$MD_INTEGRITY" "$MDP_ID" "$MD_MARK" "$MD_ATTEMPT"' \
        'inject_multidisplay "$MDP" "$MDP_ID"' \
        'bootstrap_multidisplay' \
        '[ "$MD_LAST_FAILURE" != ready_marker_missing ]' \
        '[ "$STATUS_BOOT_COMPLETED" = 1 ] || return'; do
    require_fixed "$LOAD_BIN" "$REQUIRED"
done

for REQUIRED in \
        'var READY_MARKER = "[multidisplay] hook ready v2";' \
        'var FAILURE_MARKER = "[multidisplay] hook failed v2";' \
        'overload.call.apply(overload, [receiver].concat(args))' \
        'if (installed < 1) throw new Error("no compatible isWhiteListApp overload installed")' \
        'traceWhitelist(pkg, false, "never")' \
        'traceWhitelist(pkg, true, "server-override")' \
        'var QUERY_TRACE_MAX = 20;' \
        'var method = MDI.showDelayDialog;' \
        'var method = MDI.setEnableActivityAnimation;' \
        'signalReady("core_overloads=" + coreCount'; do
    require_fixed "$MULTIDISPLAY" "$REQUIRED"
done

if grep -Fq 'inject_ret "$MDP"' "$LOAD_BIN"; then
    fail "multidisplay still uses exit-code-based generic injector"
fi

# init safety: post-fs-data guarantees mounted /data, while class late_start remains the second
# barrier. The version marker forces an existing boot_completed-gated RC to be upgraded.
for REQUIRED in \
        'on post-fs-data' \
        'MD-priority-before-app-cache-v1' \
        'enable voyahtune_load' \
        'class late_start' \
        'disabled'; do
    require_fixed "$LOAD_RC" "$REQUIRED"
done
if grep -Fq 'on property:sys.boot_completed=1' "$LOAD_RC"; then
    fail "multidisplay loader still starts after app whitelist caches"
fi
[ "$(grep -Fc 'MD-priority-before-app-cache-v1' "$FULL_INSTALL")" -eq 3 ] \
    || fail "Unix installer does not upgrade/verify the early-start RC at all three stages"
[ "$(grep -Fc 'MD-priority-before-app-cache-v1' "$FULL_INSTALL_BAT")" -eq 3 ] \
    || fail "Windows installer does not upgrade/verify the early-start RC at all three stages"
rc_setenforce_line=$(grep -nF '/system/bin/setenforce 0' "$LOAD_RC" | cut -d: -f1)
rc_enable_line=$(grep -nF '    enable voyahtune_load' "$LOAD_RC" | cut -d: -f1)
rc_service_line=$(grep -nF 'service voyahtune_load ' "$LOAD_RC" | cut -d: -f1)
[ "$(grep -Fc '    enable voyahtune_load' "$LOAD_RC")" -eq 1 ] \
    && [ "$rc_setenforce_line" -lt "$rc_enable_line" ] \
    && [ "$rc_enable_line" -lt "$rc_service_line" ] \
    || fail "loader is enabled before synchronous post-fs-data setenforce"

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

md_function=$(awk '
    /^inject_multidisplay\(\) \{/ { capture = 1 }
    /^# `timeout` uses/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
[ -n "$md_function" ] || fail "cannot extract multidisplay injector"
md_reserve_line=$(printf '%s\n' "$md_function" \
    | grep -nF 'reserve_md_injection_attempt "$MD_INJECT_ID" "$MD_ATTEMPT"' | cut -d: -f1)
md_frida_line=$(printf '%s\n' "$md_function" \
    | grep -nF 'timeout -k 5 30 "$FI"' | cut -d: -f1)
md_ready_line=$(printf '%s\n' "$md_function" \
    | grep -nF 'grep -qF "$MD_READY_MARKER " "$MD_TRY"' | cut -d: -f1)
md_mark_line=$(printf '%s\n' "$md_function" \
    | grep -nF 'mv -f "$MD_MARK_TMP" "$MD_MARK"' | cut -d: -f1)
md_agent_failure_line=$(printf '%s\n' "$md_function" \
    | grep -nF 'grep -qF "$MD_FAILURE_MARKER " "$MD_TRY"' | cut -d: -f1)
md_generic_failure_line=$(printf '%s\n' "$md_function" \
    | grep -nF "grep -qiE 'unable to find|not found|failed to|cannot parse|no such process|error resolving'" \
    | cut -d: -f1)
[ "$md_reserve_line" -lt "$md_frida_line" ] \
    || fail "multidisplay reserves after frida-inject"
[ "$md_ready_line" -lt "$md_mark_line" ] \
    || fail "multidisplay publishes active marker before exact agent readiness"
[ "$md_agent_failure_line" -lt "$md_generic_failure_line" ] \
    || fail "agent core failure is hidden by generic 'class not found' parsing"

watchdog_function=$(awk '
    /^watchdog_cycle\(\) \{/ { capture = 1 }
    /^SS=; SS_ID=/ { capture = 0 }
    capture { print }
' "$LOAD_BIN")
watchdog_md_line=$(printf '%s\n' "$watchdog_function" \
    | grep -nF 'MDP=$(pidof com.qinggan.systemservice)' | head -n1 | cut -d: -f1)
watchdog_vd_line=$(printf '%s\n' "$watchdog_function" \
    | grep -nF 'SS=$(pidof system_server)' | head -n1 | cut -d: -f1)
watchdog_launcher_line=$(printf '%s\n' "$watchdog_function" \
    | grep -nF 'LP=$(pidof com.qinggan.app.launcher)' | head -n1 | cut -d: -f1)
watchdog_defer_line=$(printf '%s\n' "$watchdog_function" \
    | grep -nF 'MD_PRIORITY_DEFER_CYCLES=$((MD_PRIORITY_DEFER_CYCLES - 1))' \
    | head -n1 | cut -d: -f1)
[ "$watchdog_md_line" -lt "$watchdog_vd_line" ] \
    && [ "$watchdog_md_line" -lt "$watchdog_launcher_line" ] \
    || fail "slow VD/launcher injection can still precede multidisplay"
[ "$watchdog_launcher_line" -lt "$watchdog_vd_line" ] \
    || fail "launcher dock can still wait behind the potentially slow VD attach"
[ "$watchdog_defer_line" -lt "$watchdog_vd_line" ] \
    || fail "absent early systemservice immediately falls into a blocking VD attach"
bootstrap_call_line=$(grep -n '^bootstrap_multidisplay$' "$LOAD_BIN" | cut -d: -f1)
watchdog_loop_line=$(grep -n '^while \[ 1 \]; do$' "$LOAD_BIN" | tail -n1 | cut -d: -f1)
[ "$bootstrap_call_line" -lt "$watchdog_loop_line" ] \
    || fail "bounded multidisplay discovery is not run before the general watchdog"
grep -Fq 'WATCHDOG_CYCLE_SECONDS=5' "$LOAD_BIN" \
    || fail "launcher restart discovery is slower than the 5-second wake target"
grep -Fq 'sleep "$WATCHDOG_CYCLE_SECONDS"' "$LOAD_BIN" \
    || fail "watchdog does not use its bounded cycle setting"

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
load_md_runtime_hook_state ok "$MD_NEW_ID" "$MD_SUCCESS_MARK" "$MD_ATTEMPT_FILE"
[ "$RUNTIME_STATE" = failed ] \
    || fail "terminal retry latch without exact ready marker was reported active"
printf '%s\n' "$MD_NEW_ID" > "$MD_SUCCESS_MARK"
load_md_runtime_hook_state ok "$MD_NEW_ID" "$MD_SUCCESS_MARK" "$MD_ATTEMPT_FILE"
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
