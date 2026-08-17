#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
APOLLO_JS="$REPO_ROOT/Packaging/inject/apollo_tech.js"
INSTALL_SH="$REPO_ROOT/Packaging/installer/full/install.sh"
INSTALL_BAT="$REPO_ROOT/Packaging/installer/full/install.bat"
LIGHT_INSTALL_SH="$REPO_ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$REPO_ROOT/Packaging/installer/light/install.bat"
REMOVE_SH="$REPO_ROOT/Packaging/installer/full/remove.sh"
REMOVE_BAT="$REPO_ROOT/Packaging/installer/full/remove.bat"
LIGHT_REMOVE_SH="$REPO_ROOT/Packaging/installer/light/remove.sh"
LIGHT_REMOVE_BAT="$REPO_ROOT/Packaging/installer/light/remove.bat"
README="$REPO_ROOT/Packaging/README.md"
NATIVE_BUILD="$REPO_ROOT/Native/app/build.gradle.kts"
RESTORE_BUILD="$REPO_ROOT/RestoreMode/app/build.gradle.kts"
NATIVE_MANIFEST="$REPO_ROOT/Native/app/src/main/AndroidManifest.xml"
NATIVE_SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloTlcService.java"
RESTORE_ACTIVITY="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
OPT_IN_KEY=open_voyah_apollo_legacy_hook_enabled

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

first_line() {
    LINE=$(grep -Fn -- "$2" "$1" | sed -n '1s/:.*//p')
    [ -n "$LINE" ] || fail "$1 does not contain: $2"
    echo "$LINE"
}

assert_before() {
    BEFORE=$(first_line "$1" "$2")
    AFTER=$(first_line "$1" "$3")
    [ "$BEFORE" -lt "$AFTER" ] || fail "$2 must appear before $3 in $1"
}

# Host-side syntax checks for every changed POSIX shell entry point.
for FILE in "$LOAD_BIN" "$INSTALL_SH" "$LIGHT_INSTALL_SH" "$REMOVE_SH" "$LIGHT_REMOVE_SH"; do
    sh -n "$FILE"
done
if command -v node >/dev/null 2>&1; then
    node --check "$APOLLO_JS"
fi

# Loader is direct-only unless the exact explicit diagnostic opt-in is 1.
require_fixed "$LOAD_BIN" "APOLLO_LEGACY_OPT_IN_KEY=$OPT_IN_KEY"
require_fixed "$LOAD_BIN" 'if [ "$APOLLO_LEGACY_OPT_IN" != "1" ]; then'
require_fixed "$LOAD_BIN" 'disable_apollo_legacy_hook'
require_fixed "$LOAD_BIN" 'APOLLO_DISABLED_MARK=/data/local/tmp/voyah_apollo.disabled'
require_fixed "$LOAD_BIN" 'am force-stop "$APOLLO_TARGET"'
require_fixed "$LOAD_BIN" 'cat /proc/sys/kernel/random/boot_id'
require_fixed "$LOAD_BIN" 'echo "v2|$IDENT_BOOT|$IDENT_PID|$IDENT_START"'
assert_before "$LOAD_BIN" 'if [ "$APOLLO_LEGACY_OPT_IN" != "1" ]; then' \
    'inject_verified_marker "$APOLLO_PID"'

# Manual attach is also fail-closed before the one-time APK hashes/OEM class resolution.
require_fixed "$APOLLO_JS" "var LEGACY_OPT_IN_KEY = \"$OPT_IN_KEY\";"
assert_before "$APOLLO_JS" 'if (readLegacyOptIn() !== true) {' \
    'var hashMatches = verifyPinnedPackages();'
assert_before "$APOLLO_JS" 'var hashMatches = verifyPinnedPackages();' \
    'BaiduProviderUtil = Java.use('

# H97X never installs the hot generic callback. Legacy callback filters pinned numeric
# VehicleState IDs and values before assigning names or scheduling main-thread work.
require_fixed "$APOLLO_JS" 'if (!legacy97CProfile) {'
require_fixed "$APOLLO_JS" '"mode=direct_h97x" : "mode=unsupported"'
assert_before "$APOLLO_JS" 'if (legacy97CProfile) {' \
    'CanBusCallback = Java.use("com.qinggan.app.basevehiclesetting.canbustools.CanBusTool$3");'
require_fixed "$APOLLO_JS" 'vehicleStateGetValue = VehicleStateClass.getValue.overload();'
require_fixed "$APOLLO_JS" 'wakeId = vehicleStateGetValue.call(state);'
require_fixed "$APOLLO_JS" 'var HUM_VCU_READY_ID = 924;'
require_fixed "$APOLLO_JS" 'var BMS_STATE_ID = 958;'
require_fixed "$APOLLO_JS" 'stateId === HUM_VCU_READY_ID && value === 1'
require_fixed "$APOLLO_JS" 'stateId === BMS_STATE_ID && value === 3'
require_fixed "$APOLLO_JS" 'Legacy generic hook всё ещё делает GumJS crossing'
assert_before "$APOLLO_JS" 'if (!legacy97CProfile) return;' \
    'wakeId = vehicleStateGetValue.call(state);'
assert_before "$APOLLO_JS" 'if (!isEligibleWakeId(wakeId, value)) return;' \
    'var wakeName = wakeId === HUM_VCU_READY_ID'

WAKE_FUNCTION=$(awk '
    /function scheduleEligibleWake\(state, value\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$APOLLO_JS")
case "$WAKE_FUNCTION" in
    *toString*) fail "scheduleEligibleWake must not stringify generic VehicleState callbacks" ;;
esac

# Single-flight coalesces an initial burst and retains one event arriving during handling.
require_fixed "$APOLLO_JS" 'if (wakeDispatchPending) {'
require_fixed "$APOLLO_JS" 'trailingWakePending = true;'
require_fixed "$APOLLO_JS" 'if (trailingWakePending) {'
require_fixed "$APOLLO_JS" 'Java.scheduleOnMainThread(dispatchPendingWake);'

# Install/update always closes opt-in and liveness before system mutation in both flavors.
for FILE in "$INSTALL_SH" "$INSTALL_BAT" "$LIGHT_INSTALL_SH" "$LIGHT_INSTALL_BAT"; do
    require_fixed "$FILE" "$OPT_IN_KEY"
    require_fixed "$FILE" 'open_voyah_apollo_profile_supported'
    require_fixed "$FILE" 'open_voyah_apollo_profile_heartbeat'
    require_fixed "$FILE" 'com.qinggan.permission.WRITE_CANBUS'
done
assert_before "$INSTALL_SH" "$OPT_IN_KEY" 'adb disable-verity'
assert_before "$INSTALL_BAT" "$OPT_IN_KEY" 'adb.exe disable-verity'
assert_before "$LIGHT_INSTALL_SH" "$OPT_IN_KEY" 'adb disable-verity'
assert_before "$LIGHT_INSTALL_BAT" "$OPT_IN_KEY" 'adb.exe disable-verity'
for FILE in "$REMOVE_SH" "$REMOVE_BAT"; do
    require_fixed "$FILE" "$OPT_IN_KEY"
    require_fixed "$FILE" 'voyah_apollo.disabled'
done
for FILE in "$LIGHT_REMOVE_SH" "$LIGHT_REMOVE_BAT"; do
    require_fixed "$FILE" "$OPT_IN_KEY"
    require_fixed "$FILE" 'open_voyah_apollo_profile_supported'
done

# Direct Apollo and its signature permission are intentionally common to full and light; only
# legacy Frida diagnostics remain full-only.
for FILE in "$NATIVE_BUILD" "$RESTORE_BUILD"; do
    DIRECT_APOLLO_FLAVORS=$(grep -F -c \
        'buildConfigField("boolean", "HAS_DIRECT_APOLLO", "true")' "$FILE")
    [ "$DIRECT_APOLLO_FLAVORS" -eq 2 ] \
        || fail "$FILE must enable HAS_DIRECT_APOLLO in exactly full and light"
done
require_fixed "$NATIVE_MANIFEST" 'android:name="com.qinggan.permission.WRITE_CANBUS"'
require_fixed "$NATIVE_SERVICE" 'BuildConfig.HAS_DIRECT_APOLLO'
require_fixed "$RESTORE_ACTIVITY" 'BuildConfig.HAS_DIRECT_APOLLO'
if grep -Fq 'BuildConfig.IS_FULL' "$NATIVE_SERVICE"; then
    fail "ApolloTlcService must not couple direct Apollo to the full flavor"
fi
require_fixed "$README" "$OPT_IN_KEY=1"
require_fixed "$README" 'generic `onVehicleStateChanged` не'
require_fixed "$README" 'Прямой H97X Binder-контур Native доступен в full и light'

echo "PASS: Apollo direct-only packaging guards"
