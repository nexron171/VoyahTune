#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
LOADER="$ROOT/Packaging/system/load.bin"
PROVIDER="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
CONTRACT="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/HookStatusContract.java"
APP_MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
FULL_INSTALL="$ROOT/Packaging/installer/device/install.sh"
FULL_INSTALL_BAT="$ROOT/Packaging/installer/device/install.bat"

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
require "$ACTIVITY" 'HookStatusContract.renderForUi(hookPayload)'
require "$ACTIVITY" 'activityResumed && currentSection == 6'
require "$ACTIVITY" 'SYSTEM_METRICS_INTERVAL_MS = 5_000L'
[ "$(grep -F -c 'postDelayed(systemMetricsTick, SYSTEM_METRICS_INTERVAL_MS)' "$ACTIVITY")" -eq 1 ] \
    || fail "hook diagnostics must reuse the only Other timer"

# Status collection/delivery owns a separate lane; Binder delays cannot block core discovery.
require "$LOADER" 'status) collect_worker_states; publish_hook_status running ;;'
require "$LOADER" 'STATUS_LOADER_PID=${SUPERVISOR_PID:-$$}'
require "$LOADER" 'mv -f "$WS_TMP" "$WORKER_PREFIX.$WORKER_LANE.state"'

require "$FULL_INSTALL" 'setprop ctl.stop voyahtune_load'
require "$FULL_INSTALL" 'getprop init.svc.voyahtune_load'
forbid "$FULL_INSTALL" 'pgrep -f'
forbid "$FULL_INSTALL" 'pkill -'
forbid "$FULL_INSTALL" 'signal_hook_runtime'

# Update safety: the process freeze is after the only possible verity reboot, before the first
# hook-state mutation, and an abort can restart the old/new init service.
[ "$(grep -Ec '^[[:space:]]*(if ! )?adb reboot' "$FULL_INSTALL")" -eq 2 ] \
    || fail "install.sh must have only verity and final reboots"
full_sh_verity=$(grep -nE '^[[:space:]]*adb reboot$' "$FULL_INSTALL" | head -n1 | cut -d: -f1)
full_sh_barrier=$(line_first "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=1')
full_sh_mutation=$(line_first "$FULL_INSTALL" 'if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then')
[ "$full_sh_verity" -lt "$full_sh_barrier" ] && [ "$full_sh_barrier" -lt "$full_sh_mutation" ] \
    || fail "install.sh freeze is not after verity reboot and before hook mutation"
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
    || fail "install.sh must wait for boot and verify Native before reporting completion"

[ "$(grep -Ec '^adb[.]exe reboot' "$FULL_INSTALL_BAT")" -eq 2 ] \
    || fail "install.bat must have only verity and final reboots"
full_bat_verity=$(grep -nF 'adb.exe reboot' "$FULL_INSTALL_BAT" | head -n1 | cut -d: -f1)
full_bat_barrier=$(line_first "$FULL_INSTALL_BAT" 'set "HOOK_UPDATE_BARRIER_ARMED=1"')
full_bat_mutation=$(line_first "$FULL_INSTALL_BAT" 'call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled')
[ "$full_bat_verity" -lt "$full_bat_barrier" ] && [ "$full_bat_barrier" -lt "$full_bat_mutation" ] \
    || fail "install.bat freeze is not after verity reboot and before hook mutation"
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
    || fail "install.bat must wait for boot and verify Native before reporting completion"
forbid_ci "$FULL_INSTALL_BAT" 'powershell'
forbid_ci "$FULL_INSTALL_BAT" 'pwsh'
forbid_ci "$FULL_INSTALL_BAT" 'cscript'

echo "PASS: direct hook install and demand-scoped status contract"
