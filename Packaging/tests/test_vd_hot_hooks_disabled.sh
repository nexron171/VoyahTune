#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VD="$ROOT/Packaging/inject/vd_bypass.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
RECEIVER="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java"
HOST="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostActivity.java"
MANIFEST="$ROOT/Native/app/src/main/AndroidManifest.xml"

fail() {
    echo "vd hot-hook safety test failed: $*" >&2
    exit 1
}

node --check "$VD"
node --check "$DOCK"

[ "$(grep -Fxc '    var SYSTEM_SERVER_FREEFORM_HOT_HOOKS = false;' "$VD")" -eq 1 ] \
    || fail "vd_bypass safety literal must be false and unique"
[ "$(grep -Fxc '    var SYSTEM_SERVER_FREEFORM_HOT_HOOKS = false;' "$DOCK")" -eq 1 ] \
    || fail "launcherdock safety literal must be false and unique"

attach_line=$(grep -nF '    function attachFreeformHotHooks(reason) {' "$VD" | cut -d: -f1)
guard_line=$(grep -nF '        if (!SYSTEM_SERVER_FREEFORM_HOT_HOOKS) return;' "$VD" | head -1 | cut -d: -f1)
layout_attach_line=$(grep -nF 'ffLayoutMethod.implementation = ffLayoutImplementation;' "$VD" | cut -d: -f1)
config_attach_line=$(grep -nF 'ffConfigMethod.implementation = ffConfigImplementation;' "$VD" | cut -d: -f1)
[ "$guard_line" -gt "$attach_line" ] || fail "hot-hook guard must be inside attach function"
[ "$guard_line" -lt "$layout_attach_line" ] || fail "layout hook can attach before safety guard"
[ "$guard_line" -lt "$config_attach_line" ] || fail "config hook can attach before safety guard"
[ "$(grep -Fc 'ffLayoutMethod.implementation = ffLayoutImplementation;' "$VD")" -eq 1 ] \
    || fail "unexpected layout attach path"
[ "$(grep -Fc 'ffConfigMethod.implementation = ffConfigImplementation;' "$VD")" -eq 1 ] \
    || fail "unexpected config attach path"

for core_hook in \
    'IMS.checkInjectEventsPermission' \
    'BinderService.checkCallingPermission' \
    'ASS.isCallerAllowedToLaunchOnDisplay' \
    'ActivityRecord.canBeLaunchedOnDisplay' \
    'PMS.hasSystemFeature(secondary_displays)'
do
    grep -Fq "$core_hook" "$VD" || fail "missing core VD hook: $core_hook"
done

grep -Fq 'return SYSTEM_SERVER_FREEFORM_HOT_HOOKS' "$DOCK" \
    || fail "floating-home policy is not tied to hot-hook safety flag"
grep -Fq 'SplitHostActivity.launchSingle(app, pkg, dpi, displayId)' "$RECEIVER" \
    || fail "single-app launch does not use one-pane VD"
grep -Fq 'public static void launchSingle' "$HOST" \
    || fail "one-pane VD entry point missing"
grep -Fq 'i.putExtra(EXTRA_RIGHT, "")' "$HOST" \
    || fail "one-pane VD must keep the right pane empty"
grep -Fq 'android:launchMode="singleTop"' "$MANIFEST" \
    || fail "one-pane VD host must reuse the active instance"
if grep -Fq 'closeActiveSplit' "$RECEIVER" "$HOST"; then
    fail "obsolete delayed split handoff is still reachable"
fi
if grep -Fq 'postDelayed' "$RECEIVER"; then
    fail "single-app receiver must not queue a stale delayed launch"
fi
grep -Fq 'VD_FLAGS_TRUSTED  = 1 | 8 | 256 | 1024' "$HOST" \
    || fail "trusted VD must destroy content when removed"
grep -Fq 'VD_FLAGS_FALLBACK = 1 | 8 | 256' "$HOST" \
    || fail "fallback VD must destroy content when removed"

echo "vd hot-hook safety test: OK"
