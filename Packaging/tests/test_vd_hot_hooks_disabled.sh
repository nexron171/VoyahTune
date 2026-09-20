#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VD="$ROOT/Packaging/inject/vd_bypass.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
RECEIVER="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java"
APP_LAUNCHER="$ROOT/Native/app/src/main/java/ru/big/town/anative/AppDisplayLauncher.java"
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

grep -Fq 'scheduleFreeformConfigReplay("config reload");' "$VD" \
    || fail "config reloads bypass boot/wake hot-hook stabilization"
grep -Fq 'if (ffLayoutAttached && ffConfigAttached) {' "$VD" \
    || fail "cache-only config changes reinstall active WindowManager hooks"
grep -Fq 'if (!ffHotAttachPending) {' "$VD" \
    || fail "config traffic can shorten an already pending boot/wake stabilization delay"
if grep -Fq 'scheduleFreeformHotAttach(0, "config reload");' "$VD"; then
    fail "startup config immediately reattaches WindowManager hot hooks"
fi
config_replay_function=$(awk '
    /^    function scheduleFreeformConfigReplay\(reason\) \{/ { capture = 1 }
    /^    function scheduleFreeformHotAttach\(delayMs, reason\) \{/ { capture = 0 }
    capture { print }
' "$VD")
[ -n "$config_replay_function" ] || fail "cannot inspect config replay implementation"
if printf '%s\n' "$config_replay_function" | grep -Fq 'detachFreeformHotHooks'; then
    fail "WIN_RELOAD detaches a live WindowManager replacement"
fi

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
grep -Fq 'if (isUserFullscreen(pkg)) return false;' "$DOCK" \
    || fail "user fullscreen package cannot release the pinned dock"
grep -Fq 'if (isUserFullscreen(pkg)) return null;' "$DOCK" \
    || fail "pending launch guard keeps the dock over a fullscreen package"
grep -Fq 'var targetLeft = fullscreen ? 0 : FF.left;' "$VD" \
    || fail "fullscreen package does not remove the dock inset"
grep -Fq 'var targetTop = FF.top;' "$VD" \
    || fail "fullscreen package can overlap the status bar"
if grep -Fq 'var targetTop = fullscreen ? 0 : FF.top;' "$VD"; then
    fail "fullscreen package removes the required status-bar inset"
fi
grep -Fq 'if (wmode == 5) { ffNote("skip-freeform", pkg, displayId, wmode); return; }' "$VD" \
    || fail "DisplayPolicy hot path attempts to mutate a real freeform task"
if grep -Fq 'wmode == 5 && !fullscreen' "$VD"; then
    fail "fullscreen allowlist bypasses the safe real-freeform guard"
fi
grep -Fq 'attrs.width.value = -1;' "$VD" \
    || fail "fullscreen package cannot override an app-requested dock-width reservation"
grep -Fq 'attrs.width.value = savedAttrWidth;' "$VD" \
    || fail "fullscreen LayoutParams override leaks into later WindowManager layouts"
grep -Fq 'ffRequestedWidthField.setInt(win, FF.right - targetLeft);' "$VD" \
    || fail "fullscreen Window frame does not propagate to its Surface requested width"
grep -Fq 'ffRequestedHeightField.setInt(win, bottom - targetTop);' "$VD" \
    || fail "fullscreen Surface height does not preserve the status-bar inset"
for window_frame in \
    mStableFrame mParentFrame mDisplayFrame mContentFrame mVisibleFrame mDecorFrame
do
    grep -Fq "wf.$window_frame.value.set(targetLeft, targetTop, FF.right, bottom);" "$VD" \
        || fail "$window_frame does not preserve the fullscreen status-bar inset"
done
grep -Fq 'if (fullscreen && wt === 1' "$VD" \
    || fail "requested Surface size override is not restricted to the main Activity window"
grep -Fq 'bundle.putInt("android.activity.windowingMode", 1);' "$APP_LAUNCHER" \
    || fail "reused freeform tasks are not normalized to Android fullscreen at launch"
grep -Fq '"ru.big.town.anative.OPEN_FULLSCREEN".equals(receivedIntent)' "$RECEIVER" \
    || fail "launcher All Apps has no validated Native fullscreen launch bridge"
grep -Fq 'isConfiguredFullscreenPackage(context, pkg)' "$RECEIVER" \
    || fail "exported fullscreen launch bridge accepts packages outside the saved allowlist"
grep -Fq 'voyahtune_fullscreen_apps' "$VD" \
    || fail "WindowManager hook does not cache fullscreen packages"
grep -Fq 'var floatHomeOff = function () { return cfg("floathome") !== "0"; };' "$DOCK" \
    || fail "floating Home suppression is not restored globally"
grep -Fq 'android.intent.action.TOP_ACTIVITY_CHANGED' "$DOCK" \
    || fail "launcher does not re-evaluate the dock after fullscreen activity transitions"
grep -Fq 'model.handleUpdateMainNavigationBar(pkg, act, true);' "$DOCK" \
    || fail "return from a fullscreen OEM activity cannot restore the main dock"
grep -Fq 'model.handleUpdateMainNavigationBar(pkg, act, false);' "$DOCK" \
    || fail "user fullscreen package does not explicitly hide the main dock"
grep -Fq 'model.handleUpdateSecondNavigationBar(pkg, act, false);' "$DOCK" \
    || fail "user fullscreen package does not explicitly hide the destination dock"
grep -Fq 'animator.removeAllListeners();' "$DOCK" \
    || fail "fullscreen hide can cancel OEM dismiss with its destructive end listener attached"
grep -Fq 'animator.removeAllUpdateListeners();' "$DOCK" \
    || fail "fullscreen hide can race an in-flight OEM window-position update"
grep -Fq 'windowManager.updateViewLayout(root, lp);' "$DOCK" \
    || fail "fullscreen hide does not synchronously place the dock Window off-screen"
grep -Fq 'windowManager.removeView(root);' "$DOCK" \
    || fail "fullscreen hide leaves the navigation-bar inset attached to WindowManager"
animator_listener_line=$(grep -nF 'animator.removeAllListeners();' "$DOCK" | cut -d: -f1)
attached_guard_line=$(grep -nF '            if (attached) {' "$DOCK" | cut -d: -f1)
dock_remove_line=$(grep -nF 'windowManager.removeView(root);' "$DOCK" | cut -d: -f1)
[ "$animator_listener_line" -lt "$dock_remove_line" ] \
    || fail "dock Window can be removed before the OEM animator end-listener is disarmed"
[ "$attached_guard_line" -lt "$dock_remove_line" ] \
    || fail "repeated fullscreen reconciliation can remove an already detached dock Window"
if grep -Fq 'windowManager.removeViewImmediate(root);' "$DOCK"; then
    fail "fullscreen hide can double-remove the dock after an animator end callback"
fi
grep -Fq 'forceHideDockController(this, "blocked show display=" + sid);' "$DOCK" \
    || fail "OEM show can resurrect a dock over a fullscreen package"
grep -Fq 'function topActivityForScreen(screenId, context) {' "$DOCK" \
    || fail "dock visibility relies only on a stale updateSelectedApp cache"
grep -Fq 'installFullscreenVisibilityGate("handleUpdateMainNavigationBar", 0);' "$DOCK" \
    || fail "queued main-display visibility requests can resurrect a fullscreen dock"
grep -Fq 'installFullscreenVisibilityGate("handleUpdateSecondNavigationBar", 1);' "$DOCK" \
    || fail "queued passenger visibility requests can resurrect a fullscreen dock"
grep -Fq 'if (forceHideDockController(controller, label)) {' "$DOCK" \
    || fail "model gate enters OEM dismiss even after a successful idempotent hide"
grep -Fq 'model.handleUpdateSecondNavigationBar(pkg, act, true);' "$DOCK" \
    || fail "return/transfer to passenger cannot restore the second dock"
grep -Fq 'var AccountConstantUtil = null;' "$DOCK" \
    || fail "optional account separator ABI can disable all transfer recovery"
grep -Fq 'if (AccountConstantUtil !== null)' "$DOCK" \
    || fail "transfer recovery dereferences an optional account helper"
grep -Fq 'launcherFloatApp.call(this, cn)' "$DOCK" \
    || fail "LauncherModel floating-home fallback recursively calls its own hook"
grep -Fq 'thirdFloatApp.call(this, cn)' "$DOCK" \
    || fail "ThirdAppUtil floating-home fallback recursively calls its own hook"
if grep -Fq 'this.isThirdShowFloatApp(cn)' "$DOCK"; then
    fail "floating-home emergency fallback still recurses into its replacement"
fi
grep -Fq 'var moveDockGuards = {' "$DOCK" \
    || fail "OEM transfer can dismiss both docks before destination foreground catches up"
grep -Fq 'move guard START source=' "$DOCK" \
    || fail "third-party transfer does not arm the cross-display dismiss guard"
grep -Fq 'LM2.onMoveStop.overloads.forEach' "$DOCK" \
    || fail "transfer guard/recovery has no matching stop lifecycle"
grep -Fq 'schedulePhysicalDockRecovery(this, "onMoveStop")' "$DOCK" \
    || fail "a missed TOP broadcast leaves the transferred-app dock hidden"
if grep -Fq 'dockPassenger' "$DOCK"; then
    fail "passenger Air/Seat must remain OEM controls, not remappable dock slots"
fi
grep -Fq 'com.qinggan.launcher.allapp.AllAppDataManager' "$DOCK" \
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
grep -Fq 'var retainedNavbar = Java.retain(inst);' "$DOCK" \
    || fail "bounded cold-boot dock passes post an unretained Java.choose wrapper"
grep -Fq 'retainedNavbar.$dispose();' "$DOCK" \
    || fail "bounded cold-boot dock passes leak retained controller wrappers"
grep -Fq 'AppLauncher.startApp(ctx(), intent, screenId);' "$DOCK" \
    || fail "All Apps click does not preserve the selected physical display"
grep -Fq 'var startAppIntent = AppLauncher.startApp.overload(' "$DOCK" \
    || fail "stock OEM All Apps entries bypass fullscreen ActivityOptions routing"
grep -Fq 'if (isUserFullscreen(pkg)) return launchFullscreen(pkg, screenId);' "$DOCK" \
    || fail "synthetic All Apps entries bypass fullscreen ActivityOptions routing"
grep -Fq 'Intent.$new("ru.big.town.anative.OPEN_FULLSCREEN")' "$DOCK" \
    || fail "launcher does not call the validated Native fullscreen bridge"
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
grep -Fq 'com.qinggan.launcher.allapp.AllAppBarView' "$DOCK" \
    || fail "All Apps synthetic clicks must be intercepted by the OEM listener owner"
grep -Fq 'allAppsAbi.adapter + '\''$AppViewHolder'\''' "$DOCK" \
    || fail "All Apps bind overload is pinned to one firmware package"
grep -Fq 'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter' "$DOCK" \
    || fail "passenger home rail must decorate the shared synthetic app list"
grep -Fq 'com.qinggan.secondlauncher.fragment.SecondMainFragment' "$DOCK" \
    || fail "passenger home rail must launch arbitrary synthetic packages on display 1"
grep -Fq '[allapps] optional passenger rail hooks unavailable:' "$DOCK" \
    || fail "optional passenger rail ABI drift must not disable full-screen All Apps"

grep -Fq 'setDockViewVisibility(views.home, 0' "$DOCK" \
    || fail "compact dock does not force Home visible"
grep -Fq 'setDockViewVisibility(views.slot1, compact && !compactSlot1 ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide an unassigned slot 1"
grep -Fq 'setDockViewVisibility(views.slot2, compact && !compactSlot2 ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide an unassigned slot 2"
if grep -Eq 'compactSlot[12].*isInstalled' "$DOCK"; then
    fail "compact slot visibility depends on transient cold-boot PackageManager readiness"
fi
grep -Fq 'setDockViewHeight(views.up, compact ? 560 : 720' "$DOCK" \
    || fail "compact/normal dock viewport heights are not applied"
grep -Fq 'setDockViewHeight(views.group, compact ? -2 : -1' "$DOCK" \
    || fail "compact buttons are not vertically centered as a wrap-content group"
grep -Fq 'var controllerShow = LiftController.show.overload();' "$DOCK" \
    || fail "cold-boot dock show is not reconciled after pre-hook screen-lift state"
grep -Fq 'setDockViewVisibility(views.extra1, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide stock slot 3"
grep -Fq 'setDockViewVisibility(views.extra2, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide stock slot 4"
grep -Fq 'setDockViewVisibility(views.allApps, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide stock All Apps"
grep -Fq 'if (sid !== 0) return; // no icon/listener/layout writes to the passenger OEM bar' "$DOCK" \
    || fail "driver dock update can mutate passenger controls"
if grep -Eq 'mScreenUp(AirView|SeatView)' "$DOCK"; then
    fail "passenger Air/Seat must remain entirely OEM-controlled"
fi
grep -Fq 'var driverTemperature = dockField(instance, "mScreenUpTemperatureContentView");' "$DOCK" \
    || fail "driver compact dock does not resolve its temperature overlay"
grep -Fq 'setDockViewVisibility(driverTemperature, compact ? 8 : 0' "$DOCK" \
    || fail "compact dock does not hide the stock driver climate overlay"
grep -Fq 'isConfiguredFullscreenPackage(app, pkg), () -> true,' "$RECEIVER" \
    || fail "launch-time windowing mode is not scoped to the saved fullscreen allowlist"
grep -Fq 'if (fullscreen) bundle.putInt' "$APP_LAUNCHER" \
    || fail "ordinary single-app launches no longer remain normal WindowManager-clamped tasks"
if grep -Fq 'setLaunchBounds' "$RECEIVER"; then
    fail "Native must not bypass the global WindowManager bounds contract"
fi
grep -Fq 'app.startActivity(intent, bundle)' "$APP_LAUNCHER" \
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
