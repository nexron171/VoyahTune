#!/system/bin/sh
set -eu
RUNTIME=${VOYAHTUNE_RUNTIME_DIRECTORY:-/data/local/bin/voyahtune-new}
STATE=${VOYAHTUNE_STATE_DIRECTORY:-/data/local/tmp/voyahtune-new}
if [ -e "$RUNTIME/agents.dex" ] || [ -e "$RUNTIME/libvoyahtune-agent.so" ] || [ -d "$STATE/residents" ]; then
    [ ! -L "$RUNTIME" ] && [ ! -L "$STATE" ] || exit 1
    [ -x "$RUNTIME/voyahtune-loader" ] && [ ! -L "$RUNTIME/voyahtune-loader" ] || {
        echo 'Native runtime cleanup requires the installed loader' >&2
        exit 1
    }
    "$RUNTIME/voyahtune-loader" --runtime-dir "$RUNTIME" --state-dir "$STATE" \
        --quiesce --control-timeout 60s >/dev/null || {
        echo 'Native helpers and target workers have not drained' >&2
        exit 1
    }
fi
