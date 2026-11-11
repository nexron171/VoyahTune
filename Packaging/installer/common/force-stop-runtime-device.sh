#!/system/bin/sh
set -eu

PROC_ROOT=${VOYAHTUNE_PROC_ROOT:-/proc}
PACKAGES=${VOYAHTUNE_PACKAGES_LIST:-/data/system/packages.list}
[ -r "$PACKAGES" ] || { echo 'Package registry is unavailable' >&2; exit 1; }

package_uid() {
    [ -r "$PACKAGES" ] || return 0
    awk -v package="$1" '$1 == package { print $2; exit }' "$PACKAGES"
}

native_uid=$(package_uid ru.big.town.anative)
restore_uid=$(package_uid ru.big.town.restoremode)
runyn_uid=$(package_uid big.town.runyn)

owned_identity() (
    directory=$1
    pid=${directory##*/}
    case "$pid" in ''|*[!0-9]*|0|1) exit 1 ;; esac
    [ "$pid" != "$$" ] || exit 1
    arguments=$(tr '\000' '\n' < "$directory/cmdline" 2>/dev/null) || exit 1
    IFS='
'
    set -f
    set -- $arguments
    [ "$#" -gt 0 ] || exit 1
    command=$1
    case "$command" in
        ru.big.town.anative|ru.big.town.anative:*)
            expected_uid=$native_uid ;;
        ru.big.town.restoremode|ru.big.town.restoremode:*)
            expected_uid=$restore_uid ;;
        big.town.runyn|big.town.runyn:*)
            expected_uid=$runyn_uid ;;
        /data/local/bin/voyahtune-new/voyahtune-loader|/data/local/bin/voyahtune-new/voyahtune-inject|/data/local/bin/voyahtune-new/voyahtune-inject-arm|/data/local/bin/load.bin|/data/local/bin/loaderFrida|loaderFrida)
            expected_uid=0 ;;
        /system/bin/sh|/system/bin/mksh|sh)
            [ "$#" = 2 ] && [ "$2" = /data/local/bin/load.bin ] || exit 1
            expected_uid=0 ;;
        /data/local/bin/frida-inject)
            expected_uid=0
            case "$arguments" in
                *vd_bypass.js*|*steeringwheelkeys.js*|*launcherdock.js*|*multidisplay.js*|*apollo_tech.js*|*voyahtune_drive_reset.js*|*voyahtune_acc_restore.js*|*keyboard_lock_en.js*|*keyboard_ru.js*|*app_client.js*|*fullscreen_client.js*) ;;
                *) exit 1 ;;
            esac ;;
        *) exit 1 ;;
    esac
    [ -n "$expected_uid" ] || exit 1
    stat=$(cat "$directory/stat" 2>/dev/null) || exit 1
    fields=${stat##*) }
    IFS=' '
    set -- $fields
    [ "$#" -ge 20 ] && [ "$1" != Z ] || exit 1
    shift 19
    ticks=$1
    uid=$(awk '$1 == "Uid:" { print $2; exit }' "$directory/status" 2>/dev/null) || exit 1
    [ "$uid" = "$expected_uid" ] || exit 1
    printf '%s:%s:%s\n' "$pid" "$ticks" "$uid"
)

# init disables restart before killing the service cgroup, including its helpers.
setprop ctl.stop voyahtune_load
attempt=0
while :; do
    remaining=
    for directory in "$PROC_ROOT"/[0-9]*; do
        identity=$(owned_identity "$directory") || continue
        [ "$(owned_identity "$directory")" = "$identity" ] || continue
        pid=${directory##*/}
        kill -9 "$pid" 2>/dev/null || {
            if [ "$(owned_identity "$directory")" = "$identity" ]; then
                printf 'Cannot kill owned runtime process: %s\n' "$identity" >&2
                exit 1
            fi
        }
        remaining="$remaining $identity"
    done
    state=$(getprop init.svc.voyahtune_load)
    if [ -z "$remaining" ] && { [ -z "$state" ] || [ "$state" = stopped ]; }; then
        exit 0
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 20 ]; then
        printf 'Owned runtime processes did not exit; init=%s processes=%s\n' "$state" "$remaining" >&2
        exit 1
    fi
    sleep 0.25
done
