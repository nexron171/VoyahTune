#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VD="$ROOT/Packaging/inject/vd_bypass.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
RECEIVER="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
HOST="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostActivity.java"
MANIFEST="$ROOT/Native/app/src/main/AndroidManifest.xml"
RESTORE_MAIN="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/MainActivity.java"

fail() {
    echo "vd/freeform hook contract test failed: $*" >&2
    exit 1
}

node --check "$VD"
node --check "$DOCK"

[ "$(grep -Fxc '    var SYSTEM_SERVER_FREEFORM_HOT_HOOKS = true;' "$VD")" -eq 1 ] \
    || fail "global Android 11 physical-window hooks must be enabled"
if grep -Fq 'SYSTEM_SERVER_FREEFORM_HOT_HOOKS' "$DOCK"; then
    fail "launcher must not independently disable global WindowManager windowing"
fi

attach_line=$(grep -nF '    function attachFreeformHotHooks(reason) {' "$VD" | cut -d: -f1)
layout_attach_line=$(grep -nF 'ffLayoutMethod.implementation = ffLayoutImplementation;' "$VD" | cut -d: -f1)
config_attach_line=$(grep -nF 'ffConfigMethod.implementation = ffConfigImplementation;' "$VD" | cut -d: -f1)
[ "$layout_attach_line" -gt "$attach_line" ] || fail "layout hook attach is outside attach function"
[ "$config_attach_line" -gt "$attach_line" ] || fail "config hook attach is outside attach function"
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

grep -Fq 'return !isStockPkg(pkg);' "$DOCK" \
    || fail "Dock pinning is not global for all non-stock apps"
grep -Fq 'var floatHomeOff = function () { return cfg("floathome") !== "0"; };' "$DOCK" \
    || fail "floating Home suppression is not restored globally"
grep -Fq 'android.intent.action.TOP_ACTIVITY_CHANGED' "$DOCK" \
    || fail "launcher does not re-evaluate the dock after fullscreen activity transitions"
grep -Fq 'this.handleUpdateMainNavigationBar(pkg, act, true);' "$DOCK" \
    || fail "return from a fullscreen OEM activity cannot restore the main dock"
grep -Fq 'com.qinggan.launcher.base.allapp.AllAppDataManager' "$DOCK" \
    || fail "third-party launchable apps are not added to the stock launcher"
grep -Fq 'if ((screenId === 0 || screenId === 1) && list !== null) addMissingApps(list);' "$DOCK" \
    || fail "All Apps injection must cover both physical displays"
[ "$(grep -Fc 'pm.getInstalledApplications(0)' "$DOCK")" -eq 1 ] \
    || fail "installed app discovery must be a single cached snapshot, not polling"
grep -Fq 'AppLauncher.startApp(ctx(), intent, screenId);' "$DOCK" \
    || fail "All Apps click does not preserve the selected physical display"
if grep -Fq 'android.activity.windowingMode' "$RECEIVER"; then
    fail "single-app launch must remain a normal task for WindowManager frame clamping"
fi
if grep -Fq 'setLaunchBounds' "$RECEIVER"; then
    fail "Native must not bypass the global WindowManager bounds contract"
fi
grep -Fq 'app.startActivity(launchIntent, optionBundle)' "$RECEIVER" \
    || fail "target package is not launched as the real top activity"
grep -Fq 'SetModesReceiverDynamic.ensureAppDpi(' "$SERVICE" \
    || fail "single-app launch discards its configured DPI"
grep -Fq 'mainHandler.postDelayed(launch, 300L)' "$SERVICE" \
    || fail "single-app launch can race the asynchronous WindowManager DPI cache reload"
if grep -Fq 'SplitHostActivity.launchSingle' "$RECEIVER"; then
    fail "single-app launch still routes through one-pane VirtualDisplay"
fi
grep -Fq 'if (right == null || right.isEmpty())' "$SERVICE" \
    || fail "single app Messenger request is not separated from VD split"
grep -Fq 'SetModesReceiverDynamic.openFreeformApp(' "$SERVICE" \
    || fail "VoyahTune single app request does not use physical target task"
grep -Fq 'sendAppWindow(pkg)' "$RESTORE_MAIN" \
    || fail "VoyahTune app tile still uses the old single-VD path"
if grep -Fq 'sendAppVd' "$RESTORE_MAIN"; then
    fail "obsolete single-app VD sender remains reachable"
fi
grep -Fq 'android:launchMode="singleTop"' "$MANIFEST" \
    || fail "VD split host must reuse the active instance"
if grep -Fq 'closeActiveSplit' "$RECEIVER" "$HOST"; then
    fail "obsolete delayed split handoff is still reachable"
fi
grep -Fq 'static boolean closeActiveHost()' "$HOST" \
    || fail "physical launch cannot retire an active VD split"
grep -Fq 'VD_FLAGS_TRUSTED  = 1 | 8 | 256 | 1024' "$HOST" \
    || fail "trusted VD must destroy content when removed"
grep -Fq 'VD_FLAGS_FALLBACK = 1 | 8 | 256' "$HOST" \
    || fail "fallback VD must destroy content when removed"

echo "vd/freeform hook contract test: OK"
