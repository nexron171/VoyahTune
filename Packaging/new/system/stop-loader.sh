#!/system/bin/sh
set -eu
setprop ctl.stop voyahtune_load
attempt=0
while [ "$(getprop init.svc.voyahtune_load)" != stopped ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 10 ] || { echo 'voyahtune_load did not stop' >&2; exit 1; }
    sleep 1
done
