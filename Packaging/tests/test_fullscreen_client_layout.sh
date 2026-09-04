#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
AGENT="$ROOT/Packaging/inject/fullscreen_client.js"
LOADER="$ROOT/Packaging/system/load.bin"
FULL_INSTALL="$ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$ROOT/Packaging/installer/full/install.bat"
FULL_REMOVE="$ROOT/Packaging/installer/full/remove.sh"
FULL_REMOVE_BAT="$ROOT/Packaging/installer/full/remove.bat"
LIGHT_INSTALL="$ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$ROOT/Packaging/installer/light/install.bat"
LIGHT_REMOVE="$ROOT/Packaging/installer/light/remove.sh"
LIGHT_REMOVE_BAT="$ROOT/Packaging/installer/light/remove.bat"
RELEASE="$ROOT/make_release.sh"

fail() { echo "fullscreen client contract test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then fail "$1: forbidden $2"; fi
}
node --check "$AGENT"
sh -n "$LOADER"
sh -n "$FULL_INSTALL"
sh -n "$FULL_REMOVE"
sh -n "$LIGHT_INSTALL"
sh -n "$LIGHT_REMOVE"

# Client geometry: never mutate app-owned LayoutParams in the hooks. Only the base Activity window
# on the two physical displays receives a cloned MATCH_PARENT width; height/status-bar geometry is
# still owned by system_server.
for REQUIRED in \
        'ViewRootImpl.setView.overload(' \
        'ViewRootImpl.setLayoutParams.overload(' \
        'Number(attrs.type.value) === TYPE_BASE_APPLICATION' \
        '(displayId === 0 || displayId === 1)' \
        'var copy = LayoutParams.$new();' \
        'copy.copyFrom(attrs);' \
        'copy.width.value = MATCH_PARENT;' \
        'WindowManagerGlobal.getInstance()' \
        'Java.cast(views.get(i), View)' \
        'view.getViewRootImpl()' \
        'windowManager.updateViewLayout(view, copy);' \
        'if (shouldReplay) replayAttachedRoots("setLayoutParams");' \
        'var key = rootKey(root);' \
        'if (typeof originalWidths[key] !== "number")' \
        'var mustRestoreRoot = !enabled && hasOriginalWidth;' \
        'delete originalWidths[key];' \
        'voyahtune_fullscreen_apps' \
        'android.permission.WRITE_SECURE_SETTINGS' \
        'application === null && bootstrapAttempt < 30' \
        'Thread.sleep(100);' \
        'argumentTypes: ["android.content.Context", "android.content.Intent"]' \
        '[fullscreen-client] hook ready v1'; do
    require "$AGENT" "$REQUIRED"
done
forbid "$AGENT" 'copy.height.value = MATCH_PARENT'
forbid "$AGENT" 'mHScale'
forbid "$AGENT" 'onReceive: function ('

receiver_line=$(grep -nF ').call(application, reloadReceiver' "$AGENT" | cut -d: -f1)
set_view_hook_line=$(grep -nF 'setView.implementation = function' "$AGENT" | cut -d: -f1)
[ "$receiver_line" -lt "$set_view_hook_line" ] \
    || fail "ViewRoot hook is installed before reversible WIN_RELOAD lifecycle"

# Loader: exact 64-bit main process only, user 0 only, one background worker and a two-rapid-restart
# circuit breaker. Agent readiness must precede the active marker.
for REQUIRED in \
        'FULLSCREEN_CLIENT=/data/local/bin/fullscreen_client.js' \
        'FULLSCREEN_CLIENT_SETTING=voyahtune_fullscreen_apps' \
        'FULLSCREEN_CLIENT_MAX_RAPID_RESTARTS=2' \
        'process_identity "$FC_CANDIDATE" "$FC_PACKAGE"' \
        'FC_CURRENT_ID=$(process_identity "$FC_TARGET_PID" "$FC_TARGET_PACKAGE"' \
        'FC_ID_BEFORE=$(process_identity "$FC_TARGET_PID" "$FC_TARGET_PACKAGE"' \
        '[ "$FC_UID" -lt 100000 ]' \
        '*app_process64) FC_INJECTOR=$FI' \
        'reserve_injection_attempt "$FC_TARGET_ID" "$FC_TARGET_ATTEMPT"' \
        'grep -qF "$FULLSCREEN_CLIENT_READY" "$FC_TRY"' \
        'inject_fullscreen_client_bg "$FC_PACKAGE"' \
        'discover_fullscreen_client' \
        'timeout -k 1 1 settings get global "$FULLSCREEN_CLIENT_SETTING"' \
        'fullscreen client blocked after rapid restarts' \
        ') &'; do
    require "$LOADER" "$REQUIRED"
done

fc_function=$(awk '
    /^inject_fullscreen_client_bg\(\) \{/ { capture = 1 }
    /^discover_fullscreen_client\(\) \{/ { capture = 0 }
    capture { print }
' "$LOADER")
reserve_line=$(printf '%s\n' "$fc_function" \
    | grep -nF 'reserve_injection_attempt "$FC_TARGET_ID"' | cut -d: -f1)
inject_line=$(printf '%s\n' "$fc_function" \
    | grep -nF 'timeout -k 5 30 "$FC_TARGET_INJECTOR"' | cut -d: -f1)
ready_line=$(printf '%s\n' "$fc_function" \
    | grep -nF 'grep -qF "$FULLSCREEN_CLIENT_READY"' | cut -d: -f1)
mark_line=$(printf '%s\n' "$fc_function" \
    | grep -nF 'mv -f "$FC_MARK_TMP" "$FC_TARGET_MARK"' | cut -d: -f1)
[ "$reserve_line" -lt "$inject_line" ] || fail "client injection is not reserved before Frida"
[ "$ready_line" -lt "$mark_line" ] || fail "client active marker precedes agent readiness"

discover_line=$(grep -nF '    discover_fullscreen_client' "$LOADER" | tail -n1 | cut -d: -f1)
keyboard_line=$(grep -nF 'KEYBOARD_SCRIPT' "$LOADER" | tail -n1 | cut -d: -f1)
publish_line=$(grep -nF '    publish_hook_status running' "$LOADER" | tail -n1 | cut -d: -f1)
[ "$keyboard_line" -lt "$discover_line" ] && [ "$discover_line" -lt "$publish_line" ] \
    || fail "optional client discovery can delay or precede core hook work"

for INSTALLER in "$FULL_INSTALL" "$FULL_INSTALL_BAT"; do
    require "$INSTALLER" 'fullscreen_client.js'
done
for CLEANER in "$FULL_REMOVE" "$FULL_REMOVE_BAT" "$LIGHT_INSTALL" "$LIGHT_INSTALL_BAT" \
        "$LIGHT_REMOVE" "$LIGHT_REMOVE_BAT"; do
    require "$CLEANER" '/data/local/bin/fullscreen_client.js'
    require "$CLEANER" 'voyahtune_fullscreen_client.*'
done
require "$RELEASE" 'fullscreen_client.js'
forbid "$LOADER" 'FI32='
forbid "$LOADER" 'app_process32'

echo "fullscreen client contract: OK"
