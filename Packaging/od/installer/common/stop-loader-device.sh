#!/system/bin/sh
# Shared with the GUI engine; target only owned loader identities.
pkill -x loaderFrida 2>/dev/null
stop_status=$?
[ "$stop_status" -le 1 ] || exit "$stop_status"
# Legacy shell supervisors have PID locks. Never scan a shell's command text for load.bin.
loader_identity() (
    case "$1" in ''|*[!0-9]*) exit 1 ;; esac
    [ "$1" != "$$" ] || exit 1
    stat=$(cat "/proc/$1/stat" 2>/dev/null) || exit 1
    fields=${stat##*) }
    set -- "$1" $fields
    pid=$1
    [ "$#" -ge 21 ] || exit 1
    shift 20
    ticks=$1
    case "$ticks" in ''|*[!0-9]*) exit 1 ;; esac
    args=$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null) || exit 1
    IFS='
'
    set -f
    set -- $args
    if [ "$#" = 2 ]; then
        case "$1" in /system/bin/sh|/system/bin/mksh|/bin/sh|sh) ;; *) exit 1 ;; esac
        [ "$2" = /data/local/bin/load.bin ] || exit 1
    elif [ "$#" = 1 ]; then
        [ "$1" = /data/local/bin/load.bin ] || exit 1
    else
        exit 1
    fi
    printf '%s\n' "$ticks"
)
for lock in /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyah_load.v2.lock /data/local/tmp/voyah_load.lock; do
    pid=
    if [ -L "$lock" ]; then pid=$(readlink "$lock");
    elif [ -f "$lock" ]; then pid=$(cat "$lock");
    elif [ -d "$lock" ]; then pid=$(cat "$lock/pid" 2>/dev/null); fi
    identity=$(loader_identity "$pid") || continue
    [ "$(loader_identity "$pid")" = "$identity" ] || continue
    kill "$pid" 2>/dev/null || { if [ -d "/proc/$pid" ]; then exit 1; fi; }
    attempts=0
    while [ "$(loader_identity "$pid")" = "$identity" ]; do
        [ "$attempts" -lt 5 ] || exit 1
        sleep 1
        attempts=$((attempts + 1))
    done
done
