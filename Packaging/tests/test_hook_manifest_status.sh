#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
MANIFEST="$ROOT/Packaging/system/voyahtune-hook-manifest.json"
LOADER="$ROOT/Packaging/system/load.bin"
PROVIDER="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
CONTRACT="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/HookStatusContract.java"
APP_MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
FULL_INSTALL="$ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$ROOT/Packaging/installer/full/install.bat"
LIGHT_INSTALL="$ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$ROOT/Packaging/installer/light/install.bat"

fail() { echo "hook manifest/status test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}
forbid_ci() {
    if grep -Fiq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}

line_first() {
    grep -nF "$2" "$1" | head -n1 | cut -d: -f1
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

[ "$(grep -F -x -c '  "schemaVersion": 1,' "$MANIFEST")" -eq 1 ] || fail "schemaVersion != 1"
[ "$(grep -F -c '{"id":' "$MANIFEST")" -eq 7 ] || fail "manifest must contain seven exact scripts"

check_entry() {
    id=$1 process=$2 script=$3
    line=$(grep -F '"id":"'"$id"'"' "$MANIFEST") || fail "missing id=$id"
    [ "$(printf '%s\n' "$line" | grep -F -c '"process":"'"$process"'","script":"'"$script"'"')" -eq 1 ] \
        || fail "$id mapping mismatch"
    expected=$(printf '%s\n' "$line" | sed -n 's/.*"sha256":"\([0-9a-f]*\)".*/\1/p')
    actual=$(sha256_file "$ROOT/Packaging/inject/$script")
    [ "${#expected}" -eq 64 ] && [ "$expected" = "$actual" ] || fail "$script SHA mismatch"
}

check_entry vd-bypass system_server vd_bypass.js
check_entry steering-wheel com.qinggan.keymanager.service steeringwheelkeys.js
check_entry launcher-dock com.qinggan.app.launcher launcherdock.js
check_entry multi-display com.qinggan.systemservice multidisplay.js
check_entry apollo-tech com.qinggan.app.vehiclesetting apollo_tech.js
check_entry keyboard-en com.qinggan.app.qgime keyboard_lock_en.js
check_entry keyboard-ru com.qinggan.app.qgime keyboard_ru.js

require "$LOADER" 'HOOK_SET_VALID=ok'
require "$LOADER" 'if [ "$HOOK_SET_VALID" != ok ]; then'
require "$LOADER" 'mv -f "$STATUS_STAGE" "$HOOK_STATUS_FILE"'
require "$LOADER" 'if [ "$HOOK_STATUS_LOCAL_PAYLOAD" != "$HOOK_STATUS_PAYLOAD" ]; then'
require "$LOADER" '/system/bin/content call --user 0'
require "$LOADER" "*'stored=true'*)"
require "$LOADER" 'HOOK_STATUS_PROVIDER_MAX_ATTEMPTS=3'
require "$LOADER" 'HOOK_STATUS_URI=content://ru.big.town.restoremode.restoremodecontentprovider'
require "$LOADER" 'HOOK_STATUS_METHOD=publishHookStatusV1'
require "$PROVIDER" 'Binder.getCallingUid() != 0'
require "$PROVIDER" '.putString(HookStatusContract.PAYLOAD_KEY, arg)'
require "$PROVIDER" '.commit();'
require "$CONTRACT" 'MAX_PAYLOAD_LENGTH = 2_048'
require "$CONTRACT" 'parts.length != 4 + HOOK_IDS.length'
require "$CONTRACT" 'AUTHORITY = "ru.big.town.restoremode.restoremodecontentprovider"'
require "$CONTRACT" 'METHOD_PUBLISH = "publishHookStatusV1"'
require "$APP_MANIFEST" 'android:authorities="ru.big.town.restoremode.restoremodecontentprovider"'

require "$LAYOUT" 'android:id="@+id/textHookStatus"'
require "$ACTIVITY" 'HookStatusContract.renderForUi(hookPayload, BuildConfig.IS_FULL)'
require "$ACTIVITY" 'activityResumed && currentSection == 6'
require "$ACTIVITY" 'SYSTEM_METRICS_INTERVAL_MS = 5_000L'
[ "$(grep -F -c 'postDelayed(systemMetricsTick, SYSTEM_METRICS_INTERVAL_MS)' "$ACTIVITY")" -eq 1 ] \
    || fail "hook diagnostics must reuse the only Other timer"

startup_publish_line=$(grep -n '^publish_hook_status running$' "$LOADER" | tail -n1 | cut -d: -f1)
watchdog_loop_line=$(grep -n '^while \[ 1 \]; do$' "$LOADER" | tail -n1 | cut -d: -f1)
[ -n "$startup_publish_line" ] && [ "$startup_publish_line" -lt "$watchdog_loop_line" ] \
    || fail "initial status must be published before the injection watchdog loop"

script_line=$(grep -n 'install_required_data_file keyboard_ru.js' "$FULL_INSTALL" | cut -d: -f1)
manifest_line=$(grep -n 'install_required_data_file voyahtune-hook-manifest.json' "$FULL_INSTALL" | tail -n1 | cut -d: -f1)
[ "$manifest_line" -gt "$script_line" ] || fail "manifest is not published after all scripts"
require "$FULL_INSTALL" "pkill -TERM -f \"/data/local/bin/load[.]bin\""
require "$FULL_INSTALL" "pkill -KILL -f \"/data/local/bin/frida[-]inject\""

# Full update safety: the process freeze is after the only possible verity reboot, before the first
# hook-state mutation, and an abort can restart the old/new init service. The manifest stays last.
[ "$(grep -Ec '^[[:space:]]*(if ! )?adb reboot' "$FULL_INSTALL")" -eq 2 ] \
    || fail "full install.sh must have only verity and final reboots"
full_sh_verity=$(grep -nE '^[[:space:]]*adb reboot$' "$FULL_INSTALL" | head -n1 | cut -d: -f1)
full_sh_barrier=$(line_first "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=1')
full_sh_mutation=$(line_first "$FULL_INSTALL" 'if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then')
[ "$full_sh_verity" -lt "$full_sh_barrier" ] && [ "$full_sh_barrier" -lt "$full_sh_mutation" ] \
    || fail "full install.sh freeze is not after verity reboot and before hook mutation"
require "$FULL_INSTALL" "adb shell 'setprop ctl.start voyahtune_load"
require "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=0'

[ "$(grep -Ec '^adb[.]exe reboot' "$FULL_INSTALL_BAT")" -eq 2 ] \
    || fail "full install.bat must have only verity and final reboots"
full_bat_verity=$(grep -nF 'adb.exe reboot' "$FULL_INSTALL_BAT" | head -n1 | cut -d: -f1)
full_bat_barrier=$(line_first "$FULL_INSTALL_BAT" 'set "HOOK_UPDATE_BARRIER_ARMED=1"')
full_bat_mutation=$(line_first "$FULL_INSTALL_BAT" 'call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled')
[ "$full_bat_verity" -lt "$full_bat_barrier" ] && [ "$full_bat_barrier" -lt "$full_bat_mutation" ] \
    || fail "full install.bat freeze is not after verity reboot and before hook mutation"
require "$FULL_INSTALL_BAT" "pkill -TERM -f '/data/local/bin/load[.]bin'"
require "$FULL_INSTALL_BAT" "pkill -KILL -f '/data/local/bin/frida[-]inject'"
require "$FULL_INSTALL_BAT" 'setprop ctl.start voyahtune_load'
forbid_ci "$FULL_INSTALL_BAT" 'powershell'
forbid_ci "$FULL_INSTALL_BAT" 'pwsh'
forbid_ci "$FULL_INSTALL_BAT" 'cscript'
require "$FULL_INSTALL_BAT" 'call :verify_hook_manifest'
require "$FULL_INSTALL_BAT" 'certutil.exe -hashfile "%~1" SHA256'
require "$FULL_INSTALL_BAT" 'for /f "usebackq skip=1 delims=" %%H'
require "$FULL_INSTALL_BAT" 'if "!HOOK_HASH_LINE:~63,1!"==""'
require "$FULL_INSTALL_BAT" 'if not "!HOOK_HASH_LINE:~64,1!"==""'
require "$FULL_INSTALL_BAT" 'for %%C in (0 1 2 3 4 5 6 7 8 9 a b c d e f A B C D E F)'
require "$FULL_INSTALL_BAT" 'findstr.exe /N "^" "voyahtune-hook-manifest.json"'
require "$FULL_INSTALL_BAT" 'findstr.exe /R /N "^$" "voyahtune-hook-manifest.json"'
require "$FULL_INSTALL_BAT" 'if not "%HOOK_MANIFEST_SOURCE_LINES%"=="12"'
require "$FULL_INSTALL_BAT" 'findstr.exe /R /X "[0-9][0-9]*:"'
require "$FULL_INSTALL_BAT" 'fc.exe /B "%HOOK_ACTUAL_NORMALIZED%" "%HOOK_EXPECTED_NORMALIZED%"'
[ "$(grep -F -c 'call :compute_sha256 ' "$FULL_INSTALL_BAT")" -eq 7 ] \
    || fail "full install.bat must hash exactly seven hook scripts"
[ "$(grep -F -c 'del "%HOOK_EXPECTED_MANIFEST%" "%HOOK_ACTUAL_NORMALIZED%" "%HOOK_EXPECTED_NORMALIZED%"' "$FULL_INSTALL_BAT")" -ge 3 ] \
    || fail "full install.bat must clean every manifest temp on entry/success/failure"
for expected_mapping in \
        '"id":"vd-bypass","process":"system_server","script":"vd_bypass.js"' \
        '"id":"steering-wheel","process":"com.qinggan.keymanager.service","script":"steeringwheelkeys.js"' \
        '"id":"launcher-dock","process":"com.qinggan.app.launcher","script":"launcherdock.js"' \
        '"id":"multi-display","process":"com.qinggan.systemservice","script":"multidisplay.js"' \
        '"id":"apollo-tech","process":"com.qinggan.app.vehiclesetting","script":"apollo_tech.js"' \
        '"id":"keyboard-en","process":"com.qinggan.app.qgime","script":"keyboard_lock_en.js"' \
        '"id":"keyboard-ru","process":"com.qinggan.app.qgime","script":"keyboard_ru.js"'; do
    require "$FULL_INSTALL_BAT" "$expected_mapping"
done
full_bat_manifest_preflight=$(line_first "$FULL_INSTALL_BAT" 'call :verify_hook_manifest')
full_bat_first_adb=$(line_first "$FULL_INSTALL_BAT" 'adb.exe root')
[ "$full_bat_manifest_preflight" -lt "$full_bat_first_adb" ] \
    || fail "full install.bat manifest preflight must run before the first ADB call"

# Full -> Light safety: stop after remount/reboot, refuse the owned legacy init.logcat path, disable
# the dedicated RC before deleting all project scripts/manifest/status, and never remove an unknown
# generic injector binary. Phase 2 intentionally has no restart path.
[ "$(grep -Ec '^[[:space:]]*(if ! )?adb reboot' "$LIGHT_INSTALL")" -eq 2 ] \
    || fail "light install.sh must have only verity and final reboots"
light_sh_verity=$(grep -nE '^[[:space:]]*adb reboot$' "$LIGHT_INSTALL" | head -n1 | cut -d: -f1)
light_sh_barrier=$(line_first "$LIGHT_INSTALL" 'LIGHT_HOOK_BARRIER_PHASE=1')
light_sh_mutation=$(line_first "$LIGHT_INSTALL" 'if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then')
light_sh_teardown=$(line_first "$LIGHT_INSTALL" 'if ! remove_full_hook_runtime_for_light; then')
[ "$light_sh_verity" -lt "$light_sh_barrier" ] \
    && [ "$light_sh_barrier" -lt "$light_sh_mutation" ] \
    && [ "$light_sh_mutation" -lt "$light_sh_teardown" ] \
    || fail "light install.sh freeze/teardown order is unsafe"
require "$LIGHT_INSTALL" '# init.logcat.sh Open Voyah:'
require "$LIGHT_INSTALL" 'voyahtune.load.rc.voyahtune-light-disabled'
require "$LIGHT_INSTALL" 'mv -f "$ACTIVE_RC" "$DISABLED_RC"'
require "$LIGHT_INSTALL" 'LOG_TAG="vt_load_bin"'
require "$LIGHT_INSTALL" 'HOOK_MANIFEST=/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL" '/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL" '/data/local/tmp/voyahtune-hook-status.v1'
require "$LIGHT_INSTALL" "pkill -KILL -f \"/data/local/bin/frida[-]inject\""
require "$LIGHT_INSTALL" 'LIGHT_HOOK_BARRIER_PHASE=0'
require "$LIGHT_INSTALL" 'install_required_system_file() {'
require "$LIGHT_INSTALL" "restorecon '\$SYSTEM_STAGE' && mv -f '\$SYSTEM_STAGE' '\$SYSTEM_TARGET' && restorecon '\$SYSTEM_TARGET' && sync && test -f '\$SYSTEM_TARGET'"
require "$LIGHT_INSTALL" "rm -f '\$SYSTEM_STAGE'"
require "$LIGHT_INSTALL" 'install_required_system_file native.apk \'
require "$LIGHT_INSTALL" '/system/priv-app/.Native.apk.voyahtune.new \'
require "$LIGHT_INSTALL" 'install_required_system_file privapp-permissions-ru.big.town.anative.xml \'
require "$LIGHT_INSTALL" '/system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new \'
require "$LIGHT_INSTALL" 'if ! adb reboot; then'
forbid "$LIGHT_INSTALL" 'adb push native.apk /system/priv-app/Native/Native.apk'
forbid "$LIGHT_INSTALL" 'adb push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml'
light_sh_native_commit=$(line_first "$LIGHT_INSTALL" 'install_required_system_file native.apk \')
light_sh_xml_commit=$(line_first "$LIGHT_INSTALL" 'install_required_system_file privapp-permissions-ru.big.town.anative.xml \')
light_sh_disarm=$(grep -nF 'LIGHT_HOOK_BARRIER_PHASE=0' "$LIGHT_INSTALL" | tail -n1 | cut -d: -f1)
light_sh_final_reboot=$(line_first "$LIGHT_INSTALL" 'if ! adb reboot; then')
[ "$light_sh_teardown" -lt "$light_sh_native_commit" ] \
    && [ "$light_sh_native_commit" -lt "$light_sh_xml_commit" ] \
    && [ "$light_sh_xml_commit" -lt "$light_sh_disarm" ] \
    && [ "$light_sh_disarm" -lt "$light_sh_final_reboot" ] \
    || fail "light install.sh system payload is not atomically committed before disarm/reboot"
if grep -Eq 'rm -f([^;]*[[:space:]])?/data/local/bin/frida-inject([[:space:];]|$)' "$LIGHT_INSTALL"; then
    fail "light install.sh blindly removes the generic frida-inject"
fi

[ "$(grep -Ec '^adb[.]exe reboot' "$LIGHT_INSTALL_BAT")" -eq 2 ] \
    || fail "light install.bat must have only verity and final reboots"
light_bat_verity=$(grep -nF 'adb.exe reboot' "$LIGHT_INSTALL_BAT" | head -n1 | cut -d: -f1)
light_bat_barrier=$(line_first "$LIGHT_INSTALL_BAT" 'set "LIGHT_HOOK_BARRIER_PHASE=1"')
light_bat_mutation=$(line_first "$LIGHT_INSTALL_BAT" 'call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled')
light_bat_teardown=$(line_first "$LIGHT_INSTALL_BAT" 'call :remove_full_hook_runtime_for_light')
[ "$light_bat_verity" -lt "$light_bat_barrier" ] \
    && [ "$light_bat_barrier" -lt "$light_bat_mutation" ] \
    && [ "$light_bat_mutation" -lt "$light_bat_teardown" ] \
    || fail "light install.bat freeze/teardown order is unsafe"
require "$LIGHT_INSTALL_BAT" '# init.logcat.sh Open Voyah:'
require "$LIGHT_INSTALL_BAT" 'voyahtune.load.rc.voyahtune-light-disabled'
require "$LIGHT_INSTALL_BAT" 'mv -f $ACTIVE_RC $DISABLED_RC'
require "$LIGHT_INSTALL_BAT" "LOG_TAG=\\\"vt_load_bin\\\""
require "$LIGHT_INSTALL_BAT" 'HOOK_MANIFEST=/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL_BAT" '/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL_BAT" '/data/local/tmp/voyahtune-hook-status.v1'
require "$LIGHT_INSTALL_BAT" "pkill -KILL -f '/data/local/bin/frida[-]inject'"
require "$LIGHT_INSTALL_BAT" 'set "LIGHT_HOOK_BARRIER_PHASE=0"'
require "$LIGHT_INSTALL_BAT" ':install_required_system_file'
require "$LIGHT_INSTALL_BAT" "restorecon '%~2' && mv -f '%~2' '%~3' && restorecon '%~3' && sync && test -f '%~3'"
require "$LIGHT_INSTALL_BAT" "rm -f '%~2'"
require "$LIGHT_INSTALL_BAT" 'call :install_required_system_file native.apk /system/priv-app/.Native.apk.voyahtune.new /system/priv-app/Native/Native.apk 644'
require "$LIGHT_INSTALL_BAT" 'call :install_required_system_file privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644'
forbid "$LIGHT_INSTALL_BAT" 'adb.exe push native.apk /system/priv-app/Native/Native.apk'
forbid "$LIGHT_INSTALL_BAT" 'adb.exe push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml'
light_bat_native_commit=$(line_first "$LIGHT_INSTALL_BAT" 'call :install_required_system_file native.apk ')
light_bat_xml_commit=$(line_first "$LIGHT_INSTALL_BAT" 'call :install_required_system_file privapp-permissions-ru.big.town.anative.xml ')
light_bat_disarm=$(grep -nF 'set "LIGHT_HOOK_BARRIER_PHASE=0"' "$LIGHT_INSTALL_BAT" | tail -n1 | cut -d: -f1)
light_bat_final_reboot=$(grep -nF 'adb.exe reboot' "$LIGHT_INSTALL_BAT" | tail -n1 | cut -d: -f1)
[ "$light_bat_teardown" -lt "$light_bat_native_commit" ] \
    && [ "$light_bat_native_commit" -lt "$light_bat_xml_commit" ] \
    && [ "$light_bat_xml_commit" -lt "$light_bat_disarm" ] \
    && [ "$light_bat_disarm" -lt "$light_bat_final_reboot" ] \
    || fail "light install.bat system payload is not atomically committed before disarm/reboot"
if LC_ALL=C tr -d '\011\012\015\040-\176' < "$LIGHT_INSTALL_BAT" | grep -q .; then
    fail "light install.bat must remain ASCII"
fi
if grep -Eq 'rm -f([^;]*[[:space:]])?/data/local/bin/frida-inject([[:space:];]|$)' "$LIGHT_INSTALL_BAT"; then
    fail "light install.bat blindly removes the generic frida-inject"
fi

echo "PASS: atomic hook manifest and demand-scoped status contract"
