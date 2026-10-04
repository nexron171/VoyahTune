#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VD="$ROOT/Packaging/inject/vd_bypass.js"

fail() {
    echo "vd physical-reparent replay contract test failed: $*" >&2
    exit 1
}

node --check "$VD"

require_one() {
    needle=$1
    message=$2
    [ "$(grep -Fc "$needle" "$VD")" -eq 1 ] || fail "$message"
}

require_one "ffDisplayChangedMethod = ARd.onDisplayChanged.overload(" \
    "ActivityRecord.onDisplayChanged(DisplayContent) hook is missing or duplicated"
require_one "ffDisplayChangedMethod.call(this, displayContent);" \
    "stock onDisplayChanged must be called exactly once"
require_one "requested.densityDpi.value = dpi;" \
    "minimal requested density override is missing"

replay_start=$(grep -nF "ffDisplayChangedMethod.implementation = function (displayContent) {" "$VD" | cut -d: -f1)
replay_end=$(grep -nF 'installed.push("ActivityRecord.onDisplayChanged(physical-reparent-replay)");' "$VD" | cut -d: -f1)

require_replay() {
    needle=$1
    message=$2
    awk -v first="$replay_start" -v last="$replay_end" -v text="$needle" '
        NR >= first && NR <= last && index($0, text) { found = 1 }
        END { exit found ? 0 : 1 }
    ' "$VD" || fail "$message"
}

require_replay "if (displayId !== 0 && displayId !== 1) return;" \
    "replay must be limited to the two physical displays"
require_replay "if (this.getDisplayId() !== displayId) return;" \
    "replay must reject a stale destination DisplayContent"
require_replay "if (ffBlacklisted(pkg)) return;" \
    "system/VoyahTune packages must not receive reparent overrides"
require_replay "var taskObject = ffTaskField.get(this);" \
    "replay must resolve the destination ActivityRecord task"
require_replay "if (taskObject === null) return;" \
    "detached ActivityRecords must fail open"
require_replay "if (!FF.on || !FF.screenOn || ffDisplayChangedApplying) return;" \
    "replay needs emergency-disable, screen-state and recursion guards"
require_replay "ffDisplayChangedApplying = true;" \
    "replay recursion latch is never engaged"
require_replay "ffApplyTaskDpi(taskObject, pkg);" \
    "configured DPI is not re-applied after a physical reparent"
require_replay 'requestFreeformTraversalOnce("physical reparent pkg=" + pkg' \
    "physical bounds are not replayed through the ordinary WM traversal"

original_line=$(grep -nF "ffDisplayChangedMethod.call(this, displayContent);" "$VD" | cut -d: -f1)
guard_line=$(grep -nF "if (!FF.on || !FF.screenOn || ffDisplayChangedApplying) return;" "$VD" | cut -d: -f1)
[ "$original_line" -lt "$guard_line" ] \
    || fail "the stock display move must finish before any custom guard/replay"

if grep -Eq 'task\.setBounds|task\.setAppBounds|this\.onConfigurationChanged\(' "$VD"; then
    fail "reparent replay must not freeze task bounds or mutate resolved ActivityRecord configuration"
fi

grep -Fq "finally {" "$VD" || fail "recursion latch needs a finally path"
[ "$(grep -Fc "ffDisplayChangedApplying = false;" "$VD")" -ge 2 ] \
    || fail "recursion latch is not cleared on both normal and exceptional paths"

echo "vd physical-reparent replay contract test: OK"
