#!/system/bin/sh
set -eu
RUNTIME=/data/local/bin/voyahtune-new
"$RUNTIME/voyahtune-loader" --runtime-dir "$RUNTIME" --quiesce --control-timeout 60s >/dev/null
setprop ctl.stop voyahtune_load
attempt=0
while [ "$(getprop init.svc.voyahtune_load)" != stopped ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 10 ] || { echo 'voyahtune_load did not stop' >&2; exit 1; }
    sleep 1
done
