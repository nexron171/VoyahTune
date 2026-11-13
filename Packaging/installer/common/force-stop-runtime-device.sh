#!/system/bin/sh
set -eu
set -f

PROC_ROOT=${VOYAHTUNE_PROC_ROOT:-/proc}
PACKAGES=${VOYAHTUNE_PACKAGES_LIST:-/data/system/packages.list}
UPTIME_SOURCE=${VOYAHTUNE_UPTIME_SOURCE:-/proc/uptime}

fail_stop() {
    printf 'Runtime stop: %s\n' "$*" >&2
    exit 1
}

[ -r "$PACKAGES" ] || fail_stop 'Package registry is unavailable'
native_uid=
restore_uid=
runyn_uid=
while read -r package_name package_uid unused; do
    case "$package_name" in
        ru.big.town.anative) native_uid=$package_uid ;;
        ru.big.town.restoremode) restore_uid=$package_uid ;;
        big.town.runyn) runyn_uid=$package_uid ;;
    esac
done < "$PACKAGES"

read_clock() {
    read -r uptime unused < "$UPTIME_SOURCE" || fail_stop 'uptime is unavailable'
    whole=${uptime%.*}
    fraction=${uptime#*.}
    case "$whole" in ''|*[!0-9]*) fail_stop 'invalid uptime' ;; esac
    case "$fraction" in [0-9][0-9]) ;; *) fail_stop 'invalid uptime' ;; esac
    clock_ticks=$((whole * 100 + 1${fraction} - 100))
}

stop_timeout_seconds=5
read_clock
stop_deadline=$((clock_ticks + stop_timeout_seconds * 100))
pending=
service_state=unknown

check_deadline() {
    read_clock
    [ "$clock_ticks" -lt "$stop_deadline" ] ||
        fail_stop "exceeded $stop_timeout_seconds seconds; init=$service_state remaining=$pending; reboot required"
}

expected_owner() {
    expected_uid=
    case "$1" in
        ru.big.town.anative|ru.big.town.anative:*) expected_uid=$native_uid ;;
        ru.big.town.restoremode|ru.big.town.restoremode:*) expected_uid=$restore_uid ;;
        big.town.runyn|big.town.runyn:*) expected_uid=$runyn_uid ;;
        /data/local/bin/voyahtune-new/voyahtune-loader|/data/local/bin/voyahtune-new/voyahtune-inject|/data/local/bin/voyahtune-new/voyahtune-inject-arm|/data/local/bin/load.bin|/data/local/bin/loaderFrida|loaderFrida)
            expected_uid=0 ;;
        /system/bin/sh|/system/bin/mksh|sh)
            [ "$#" = 2 ] && [ "$2" = /data/local/bin/load.bin ] || return 1
            expected_uid=0 ;;
        /data/local/bin/frida-inject)
            case "$*" in
                *vd_bypass.js*|*steeringwheelkeys.js*|*launcherdock.js*|*multidisplay.js*|*apollo_tech.js*|*voyahtune_drive_reset.js*|*voyahtune_acc_restore.js*|*keyboard_lock_en.js*|*keyboard_ru.js*|*app_client.js*|*fullscreen_client.js*)
                    expected_uid=0 ;;
            esac ;;
    esac
    [ -n "$expected_uid" ]
}

read_identity() {
    identity_directory="$PROC_ROOT/$1"
    if ! IFS= read -r process_stat 2>/dev/null < "$identity_directory/stat"; then
        [ ! -d "$identity_directory" ] && return 1
        fail_stop "cannot read identity of PID $1"
    fi
    process_fields=${process_stat##*) }
    set -- $process_fields
    [ "$#" -ge 20 ] || fail_stop "invalid identity: $identity_directory"
    [ "$1" != Z ] || return 1
    shift 19
    process_ticks=$1
    process_uid=
    if [ -r "$identity_directory/status" ]; then
        while read -r field value unused; do
            if [ "$field" = Uid: ]; then
                process_uid=$value
                break
            fi
        done < "$identity_directory/status"
    fi
    case "$process_uid" in
        ''|*[!0-9]*)
            [ ! -d "$identity_directory" ] && return 1
            fail_stop "cannot read UID: $identity_directory" ;;
    esac
    observed_identity="${identity_directory##*/}:$process_ticks:$process_uid"
}

stop_snapshot() {
    process_snapshot=$(ps -A -w -o PID,UID,CMDLINE) || fail_stop 'ps could not list processes'
    snapshot_found=0
    while read -r candidate_pid candidate_uid candidate_command candidate_arguments; do
        case "$candidate_pid" in ''|*[!0-9]*|0|1) continue ;; esac
        [ "$candidate_pid" != "$$" ] || continue
        expected_owner "$candidate_command" "$candidate_arguments" || continue
        [ "$candidate_uid" = "$expected_uid" ] || continue

        # Recheck only candidates; ps is discovery, not proof of current ownership.
        if ! arguments=$(tr '\000' '\n' 2>/dev/null < "$PROC_ROOT/$candidate_pid/cmdline"); then
            [ ! -d "$PROC_ROOT/$candidate_pid" ] && continue
            fail_stop "cannot read command of PID $candidate_pid"
        fi
        IFS='
'
        set -- $arguments
        unset IFS
        [ "$#" -gt 0 ] || continue
        expected_owner "$@" || continue
        read_identity "$candidate_pid" || continue
        [ "$process_uid" = "$expected_uid" ] || continue
        stop_identity=$observed_identity
        check_deadline
        if ! kill -9 "$candidate_pid" 2>/dev/null; then
            if read_identity "$candidate_pid" && [ "$observed_identity" = "$stop_identity" ]; then
                fail_stop "SIGKILL failed for PID $candidate_pid"
            fi
            continue
        fi
        pending="$pending $stop_identity"
        snapshot_found=1
    done <<EOF
$process_snapshot
EOF
}

service_state=$(getprop init.svc.voyahtune_load) || fail_stop 'getprop failed'
if [ -n "$service_state" ]; then
    setprop ctl.stop voyahtune_load || fail_stop 'setprop could not stop voyahtune_load'
fi
stop_snapshot

while :; do
    remaining=
    for saved_identity in $pending; do
        process_id=${saved_identity%%:*}
        if read_identity "$process_id" && [ "$observed_identity" = "$saved_identity" ]; then
            remaining="$remaining $saved_identity"
        fi
    done
    pending=$remaining
    service_state=$(getprop init.svc.voyahtune_load) || fail_stop 'getprop failed'
    if [ -z "$pending" ] && { [ -z "$service_state" ] || [ "$service_state" = stopped ]; }; then
        [ "$snapshot_found" = 1 ] || exit 0
        # Catch children created while the launchers were being terminated.
        check_deadline
        stop_snapshot
        [ -n "$pending" ] || exit 0
        continue
    fi
    check_deadline
    sleep 0.05
done
