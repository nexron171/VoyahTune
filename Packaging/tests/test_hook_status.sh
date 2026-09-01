#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
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

fail() { echo "hook status/install test failed: $*" >&2; exit 1; }
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

forbid "$LOADER" 'HOOK_MANIFEST'
forbid "$LOADER" 'sha256_path'
forbid "$LOADER" 'INTEGRITY'
forbid "$LOADER" 'manifest='
require "$FULL_INSTALL" 'rm -f /data/local/bin/voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL" 'install_required_data_file voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL" 'host_sha256'
forbid "$FULL_INSTALL" 'verify_hook_manifest'
require "$FULL_INSTALL_BAT" 'rm -f /data/local/bin/voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL_BAT" 'call :install_required_data_file voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL_BAT" 'compute_sha256'
forbid "$FULL_INSTALL_BAT" 'certutil.exe -hashfile'
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
require "$CONTRACT" 'parts.length != 3 + HOOK_IDS.length'
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

require "$FULL_INSTALL" 'setprop ctl.stop voyahtune_load'
require "$FULL_INSTALL" 'getprop init.svc.voyahtune_load'
forbid "$FULL_INSTALL" 'pgrep -f'
forbid "$FULL_INSTALL" 'pkill -'
forbid "$FULL_INSTALL" 'signal_hook_runtime'

# Full update safety: the process freeze is after the only possible verity reboot, before the first
# hook-state mutation, and an abort can restart the old/new init service.
[ "$(grep -Ec '^[[:space:]]*(if ! )?adb reboot' "$FULL_INSTALL")" -eq 2 ] \
    || fail "full install.sh must have only verity and final reboots"
full_sh_verity=$(grep -nE '^[[:space:]]*adb reboot$' "$FULL_INSTALL" | head -n1 | cut -d: -f1)
full_sh_barrier=$(line_first "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=1')
full_sh_mutation=$(line_first "$FULL_INSTALL" 'if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then')
[ "$full_sh_verity" -lt "$full_sh_barrier" ] && [ "$full_sh_barrier" -lt "$full_sh_mutation" ] \
    || fail "full install.sh freeze is not after verity reboot and before hook mutation"
require "$FULL_INSTALL" "adb shell 'setprop ctl.start voyahtune_load"
require "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=0'
require "$FULL_INSTALL" 'Ожидание загрузки устройства для проверки целостности установки...'
require "$FULL_INSTALL" 'Установка завершена и проверена.'
full_sh_final_reboot=$(grep -nE '^[[:space:]]*(if ! )?adb reboot' "$FULL_INSTALL" | tail -n1 | cut -d: -f1)
full_sh_integrity_wait=$(line_first "$FULL_INSTALL" 'Ожидание загрузки устройства для проверки целостности установки...')
full_sh_boot_wait=$(line_first "$FULL_INSTALL" 'if ! wait_for_android_boot; then')
full_sh_native_check=$(line_first "$FULL_INSTALL" 'if ! ensure_native_user_ready; then')
full_sh_complete=$(line_first "$FULL_INSTALL" 'Установка завершена и проверена.')
[ "$full_sh_final_reboot" -lt "$full_sh_integrity_wait" ] \
    && [ "$full_sh_integrity_wait" -lt "$full_sh_boot_wait" ] \
    && [ "$full_sh_boot_wait" -lt "$full_sh_native_check" ] \
    && [ "$full_sh_native_check" -lt "$full_sh_complete" ] \
    || fail "full install.sh must wait for boot and verify Native before reporting completion"

[ "$(grep -Ec '^adb[.]exe reboot' "$FULL_INSTALL_BAT")" -eq 2 ] \
    || fail "full install.bat must have only verity and final reboots"
full_bat_verity=$(grep -nF 'adb.exe reboot' "$FULL_INSTALL_BAT" | head -n1 | cut -d: -f1)
full_bat_barrier=$(line_first "$FULL_INSTALL_BAT" 'set "HOOK_UPDATE_BARRIER_ARMED=1"')
full_bat_mutation=$(line_first "$FULL_INSTALL_BAT" 'call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled')
[ "$full_bat_verity" -lt "$full_bat_barrier" ] && [ "$full_bat_barrier" -lt "$full_bat_mutation" ] \
    || fail "full install.bat freeze is not after verity reboot and before hook mutation"
require "$FULL_INSTALL_BAT" 'setprop ctl.stop voyahtune_load'
require "$FULL_INSTALL_BAT" 'getprop init.svc.voyahtune_load'
forbid "$FULL_INSTALL_BAT" 'pgrep -f'
forbid "$FULL_INSTALL_BAT" 'pkill -'
forbid "$FULL_INSTALL_BAT" 'signal_hook_runtime'
require "$FULL_INSTALL_BAT" 'setprop ctl.start voyahtune_load'
require "$FULL_INSTALL_BAT" 'Waiting for the device to boot to verify installation integrity...'
require "$FULL_INSTALL_BAT" 'Installation complete and verified.'
full_bat_final_reboot=$(grep -nF 'adb.exe reboot' "$FULL_INSTALL_BAT" | tail -n1 | cut -d: -f1)
full_bat_integrity_wait=$(line_first "$FULL_INSTALL_BAT" 'Waiting for the device to boot to verify installation integrity...')
full_bat_boot_wait=$(line_first "$FULL_INSTALL_BAT" 'call :wait_android_boot')
full_bat_native_check=$(line_first "$FULL_INSTALL_BAT" 'call :ensure_native_user_ready')
full_bat_complete=$(line_first "$FULL_INSTALL_BAT" 'Installation complete and verified.')
[ "$full_bat_final_reboot" -lt "$full_bat_integrity_wait" ] \
    && [ "$full_bat_integrity_wait" -lt "$full_bat_boot_wait" ] \
    && [ "$full_bat_boot_wait" -lt "$full_bat_native_check" ] \
    && [ "$full_bat_native_check" -lt "$full_bat_complete" ] \
    || fail "full install.bat must wait for boot and verify Native before reporting completion"
forbid_ci "$FULL_INSTALL_BAT" 'powershell'
forbid_ci "$FULL_INSTALL_BAT" 'pwsh'
forbid_ci "$FULL_INSTALL_BAT" 'cscript'

# Full -> Light safety: stop after remount/reboot, refuse the owned legacy init.logcat path, disable
# the dedicated RC before deleting all project scripts/status, and never remove an unknown
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
require "$LIGHT_INSTALL" 'LOAD_LOCK=/data/local/tmp/voyahtune_load.v2.lock'
require "$LIGHT_INSTALL" '/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL" '/data/local/tmp/voyahtune-hook-status.v1'
require "$LIGHT_INSTALL" 'setprop ctl.stop voyahtune_load'
require "$LIGHT_INSTALL" 'getprop init.svc.voyahtune_load'
forbid "$LIGHT_INSTALL" 'pgrep -f'
forbid "$LIGHT_INSTALL" 'pkill -'
forbid "$LIGHT_INSTALL" 'signal_hook_runtime'
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
require "$LIGHT_INSTALL_BAT" 'LOAD_LOCK=/data/local/tmp/voyahtune_load.v2.lock'
require "$LIGHT_INSTALL_BAT" '/data/local/bin/voyahtune-hook-manifest.json'
require "$LIGHT_INSTALL_BAT" '/data/local/tmp/voyahtune-hook-status.v1'
require "$LIGHT_INSTALL_BAT" 'setprop ctl.stop voyahtune_load'
require "$LIGHT_INSTALL_BAT" 'getprop init.svc.voyahtune_load'
forbid "$LIGHT_INSTALL_BAT" 'pgrep -f'
forbid "$LIGHT_INSTALL_BAT" 'pkill -'
forbid "$LIGHT_INSTALL_BAT" 'signal_hook_runtime'
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

echo "PASS: direct hook install and demand-scoped status contract"
