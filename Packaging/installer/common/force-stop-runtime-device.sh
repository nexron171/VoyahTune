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

monotonic_ticks() {
    read -r uptime unused < "${VOYAHTUNE_UPTIME_SOURCE:-/proc/uptime}" || return 1
    whole=${uptime%.*}
    fraction=${uptime#*.}
    case "$whole" in ''|*[!0-9]*) return 1 ;; esac
    case "$fraction" in [0-9][0-9]) ;; *) return 1 ;; esac
    printf '%s\n' "$((whole * 100 + 1${fraction} - 100))"
}

abort_timeout_seconds=60
freeze_attempt_limit=$((abort_timeout_seconds * 10))
stop_attempt_limit=$((abort_timeout_seconds * 4))
abort_started=$(monotonic_ticks)
abort_deadline=$((abort_started + abort_timeout_seconds * 100))
require_abort_time() {
    abort_now=$(monotonic_ticks) || { echo 'Runtime detach clock unavailable' >&2; exit 1; }
    [ "$abort_now" -lt "$abort_deadline" ] || {
        echo "Runtime stop exceeded $abort_timeout_seconds seconds; reboot required" >&2
        exit 1
    }
}

owned_identity() (
    directory=$1
    pid=${directory##*/}
    case "$pid" in ''|*[!0-9]*|0|1) exit 1 ;; esac
    [ "$pid" != "$$" ] || exit 1
    arguments=$(tr '\000' '\n' < "$directory/cmdline" 2>/dev/null) || { [ ! -d "$directory" ] && exit 1; exit 2; }
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
    stat=$(cat "$directory/stat" 2>/dev/null) || { [ ! -d "$directory" ] && exit 1; exit 2; }
    fields=${stat##*) }
    IFS=' '
    set -- $fields
    [ "$#" -ge 20 ] || exit 2
    [ "$1" != Z ] || exit 1
    shift 19
    ticks=$1
    uid=$(awk '$1 == "Uid:" { print $2; exit }' "$directory/status" 2>/dev/null) || { [ ! -d "$directory" ] && exit 1; exit 2; }
    case "$uid" in ''|*[!0-9]*) exit 2 ;; esac
    [ "$uid" = "$expected_uid" ] || exit 1
    printf '%s:%s:%s\n' "$pid" "$ticks" "$uid"
)

read_owned_identity() {
    if identity=$(owned_identity "$1"); then return 0; else identity_error=$?; fi
    [ "$identity_error" = 1 ] || { echo "Runtime identity unreadable: $1; reboot required" >&2; exit 1; }
    return 1
}

matches_identity() {
    if actual_identity=$(owned_identity "$1"); then
        [ "$actual_identity" = "$2" ]
        return
    else
        identity_error=$?
    fi
    [ "$identity_error" = 1 ] || { echo "Runtime identity unreadable: $1; reboot required" >&2; exit 1; }
    return 1
}

# Freeze launchers before inspecting helpers: a stopped helper cannot enter ptrace.
threads_stopped() {
    found_thread=0
    for thread in "$1"/task/[0-9]*; do
        [ -d "$thread" ] || continue
        found_thread=1
        state=$(awk '$1 == "State:" { print $2; exit }' "$thread/status") || return 1
        case "$state" in T|t|Z) ;; *) return 1 ;; esac
    done
    [ "$found_thread" = 1 ]
}

has_tracees() {
    tids=
    for thread in "$1"/task/[0-9]*; do
        [ -d "$thread" ] && tids="$tids ${thread##*/}"
    done
    [ -n "$tids" ] || return 2
    awk -v tids="$tids" '
        BEGIN {
            split(tids, values, " ")
            for (entry in values) owned[values[entry]] = 1
            matched = 0
            for (entry = 1; entry < ARGC; entry++) {
                path = ARGV[entry]
                readable = 0
                while ((result = getline line < path) > 0) {
                    split(line, fields, /[ \t]+/)
                    if (fields[1] == "TracerPid:") {
                        if (fields[2] !~ /^[0-9]+$/) exit 2
                        readable = 1
                        if (fields[2] != "0" && owned[fields[2]]) matched = 1
                    }
                }
                close(path)
                if ((result < 0 || !readable) && system("test ! -e \"" path "\"") != 0) exit 2
            }
            exit matched ? 0 : 1
        }
    ' "$PROC_ROOT"/[0-9]*/task/[0-9]*/status
}

previous=
attempt=0
while :; do
    require_abort_time
    frozen=
    stopped=1
    for directory in "$PROC_ROOT"/[0-9]*; do
        require_abort_time
        read_owned_identity "$directory" || continue
        case "$identity" in *:0) ;; *) continue ;; esac
        matches_identity "$directory" "$identity" || continue
        if ! kill -STOP "${directory##*/}" 2>/dev/null; then
            ! matches_identity "$directory" "$identity" || {
                echo "Cannot freeze runtime process $identity; reboot required" >&2
                exit 1
            }
            continue
        fi
        frozen="$frozen $identity"
        threads_stopped "$directory" || stopped=0
    done
    if [ "$stopped" = 1 ] && [ "$frozen" = "$previous" ]; then break; fi
    previous=$frozen
    attempt=$((attempt + 1))
    [ "$attempt" -lt "$freeze_attempt_limit" ] || { echo 'Runtime launchers did not freeze; reboot required' >&2; exit 1; }
    sleep 0.1
done

# SIGTERM requests native cancellation; only detach permits cgroup termination.
attempt=0
while :; do
    require_abort_time
    pending=
    for identity in $frozen; do
        require_abort_time
        pid=${identity%%:*}
        directory="$PROC_ROOT/$pid"
        matches_identity "$directory" "$identity" || continue
        if ! kill -STOP "$pid" 2>/dev/null; then
            ! matches_identity "$directory" "$identity" || {
                echo "Cannot freeze tracer $identity; reboot required" >&2
                exit 1
            }
            continue
        fi
        if ! threads_stopped "$directory"; then
            pending="$pending $identity"
            continue
        fi
        traced=0
        has_tracees "$directory" || traced=$?
        case "$traced" in
            0) ;;
            1) continue ;;
            *) echo "Cannot inspect tracer $identity; reboot required" >&2; exit 1 ;;
        esac
        command=$(tr '\000' '\n' < "$directory/cmdline" | head -n 1)
        case "$command" in
            /data/local/bin/voyahtune-new/voyahtune-inject|/data/local/bin/voyahtune-new/voyahtune-inject-arm) ;;
            *) echo "Legacy tracer $identity cannot safely detach; reboot required" >&2; exit 1 ;;
        esac
        kill -TERM "$pid" 2>/dev/null || true
        kill -CONT "$pid" 2>/dev/null || true
        pending="$pending $identity"
    done
    [ -n "$pending" ] || break
    attempt=$((attempt + 1))
    [ "$attempt" -lt "$stop_attempt_limit" ] || { echo "Native tracers did not detach; reboot required:$pending" >&2; exit 1; }
    sleep 0.25
done

# Every owned tracer is detached and frozen before init kills the service cgroup.
require_abort_time
setprop ctl.stop voyahtune_load
attempt=0
while :; do
    require_abort_time
    remaining=
    for directory in "$PROC_ROOT"/[0-9]*; do
        require_abort_time
        read_owned_identity "$directory" || continue
        matches_identity "$directory" "$identity" || continue
        pid=${directory##*/}
        require_abort_time
        kill -9 "$pid" 2>/dev/null || {
            if matches_identity "$directory" "$identity"; then
                printf 'Cannot kill owned runtime process: %s\n' "$identity" >&2
                exit 1
            fi
        }
        remaining="$remaining $identity"
    done
    require_abort_time
    state=$(getprop init.svc.voyahtune_load)
    require_abort_time
    if [ -z "$remaining" ] && { [ -z "$state" ] || [ "$state" = stopped ]; }; then
        exit 0
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$stop_attempt_limit" ]; then
        printf 'Owned runtime processes did not exit; init=%s processes=%s\n' "$state" "$remaining" >&2
        exit 1
    fi
    require_abort_time
    sleep 0.25
done
