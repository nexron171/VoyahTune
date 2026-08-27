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
    || fail "installed app discovery must have one event-invalidated snapshot builder, not polling"
grep -Fq 'var reloadData = Data.reload.overload();' "$DOCK" \
    || fail "package changes cannot rebuild both OEM All Apps lists"
grep -Fq 'name: "ru.big.town.dock.AllAppsPackageReceiver"' "$DOCK" \
    || fail "All Apps package lifecycle receiver is not registered in the launcher process"
for package_action in PACKAGE_ADDED PACKAGE_REMOVED PACKAGE_CHANGED; do
    grep -Fq "android.intent.action.$package_action" "$DOCK" \
        || fail "All Apps cache does not react to $package_action"
done
grep -Fq 'packageFilter.addDataScheme("package");' "$DOCK" \
    || fail "package lifecycle receiver is missing the mandatory package data scheme"
grep -Fq 'if (packageRefreshTimer !== null) clearTimeout(packageRefreshTimer);' "$DOCK" \
    || fail "APK update REMOVE+ADD bursts must be coalesced without polling"
grep -Fq 'installedSnapshot = null;' "$DOCK" \
    || fail "package lifecycle events do not invalidate the installed-app snapshot"
grep -Fq 'invalidateIconCache(packageName);' "$DOCK" \
    || fail "updated/removed package icons remain pinned in the launcher cache"
grep -Fq 'reloadData.call(Data);' "$DOCK" \
    || fail "package lifecycle events never invoke the OEM list reload"
grep -Fq 'Java.scheduleOnMainThread(function () {' "$DOCK" \
    || fail "AllAppDataManager reload must be dispatched on the launcher UI thread"
grep -Fq 'AppLauncher.startApp(ctx(), intent, screenId);' "$DOCK" \
    || fail "All Apps click does not preserve the selected physical display"
grep -Fq 'if (screenId !== 0 && screenId !== 1)' "$DOCK" \
    || fail "All Apps must reject accidental non-physical launch destinations"
grep -Fq 'var display = view.getDisplay();' "$DOCK" \
    || fail "All Apps click needs a physical-view fallback when its owner screen id is unavailable"
grep -Fq 'var rawScreenId = fieldValue(owner, "mScreenId");' "$DOCK" \
    || fail "All Apps click must derive its primary destination from the owner view screen id"
if grep -Fq 'var screenId = Number(fieldValue(this, "mScreenId"));' "$DOCK"; then
    fail "AllAppAdapter has no mScreenId; missing fields must not silently route passenger clicks to display 0"
fi
grep -Fq 'var bean = AppBean.$new(template.icon, template.name, pkg);' "$DOCK" \
    || fail "synthetic All Apps entries need valid OEM placeholder resources before stock bind"
grep -Fq 'template = findAppTemplate(getAll.call(Data, 0));' "$DOCK" \
    || fail "an empty passenger OEM list needs a safe main-list resource template fallback"
if grep -Fq 'var bean = AppBean.$new(0, 0, pkg);' "$DOCK"; then
    fail "zero All Apps resources crash the OEM adapter before custom icon/label replacement"
fi
grep -Fq "'int', 'java.util.List'" "$DOCK" \
    || fail "All Apps payload binds must re-apply third-party icons after theme/state updates"
grep -Fq 'com.qinggan.launcher.base.allapp.AllAppBarView' "$DOCK" \
    || fail "All Apps synthetic clicks must be intercepted by the OEM listener owner"
grep -Fq 'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter' "$DOCK" \
    || fail "passenger home rail must decorate the shared synthetic app list"
grep -Fq 'com.qinggan.secondlauncher.fragment.SecondMainFragment' "$DOCK" \
    || fail "passenger home rail must launch arbitrary synthetic packages on display 1"
grep -Fq '[allapps] optional passenger rail hooks unavailable:' "$DOCK" \
    || fail "optional passenger rail ABI drift must not disable full-screen All Apps"

for required_compact_view in home item1 item2 allApps; do
    grep -Fq "setDockViewVisibility($required_compact_view, 0" "$DOCK" \
        || fail "compact dock does not force $required_compact_view visible"
done
grep -Fq 'setDockViewHeight(up, compact ? 560 : 720' "$DOCK" \
    || fail "compact/normal dock viewport heights are not applied"
grep -Fq 'setDockViewVisibility(item3, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide stock slot 3"
grep -Fq 'setDockViewVisibility(item4, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide stock slot 4"
grep -Fq 'if (av && sid === 0)' "$DOCK" \
    || fail "All Apps long tap must be driver-only"
grep -Fq 'av.setOnLongClickListener(null);' "$DOCK" \
    || fail "passenger All Apps retains a long-click listener"
grep -Fq 'sv1.setOnLongClickListener(null); sv1.setLongClickable(false);' "$DOCK" \
    || fail "passenger slot 1 retains long-click behavior"
grep -Fq 'sv2.setOnLongClickListener(null); sv2.setLongClickable(false);' "$DOCK" \
    || fail "passenger slot 2 retains long-click behavior"
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
