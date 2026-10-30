set -e

if [ -e /data/local/bin/voyahtune-update.block ]; then
    setprop ctl.stop voyahtune_load
    attempts=0
    while [ "$(getprop init.svc.voyahtune_load)" != stopped ]; do
        attempts=$((attempts + 1))
        if [ "$attempts" -ge 30 ]; then
            echo "Restored runtime could not stop before reopening admission" >&2
            exit 1
        fi
        sleep 1
    done
    rm -f /data/local/bin/voyahtune-update.block
    sync
fi

# Starting an already running init service is a no-op.
setprop ctl.start voyahtune_load
