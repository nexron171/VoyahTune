#!/bin/sh
# Run on macOS/Linux, or directly on Android mksh:
# TMPDIR=/data/local/tmp sh test_loader_pipe_records.sh /data/local/bin/load.bin
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
LOADER=${1:-$ROOT/Packaging/system/load.bin}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/voyahtune-records.XXXXXX")
trap 'rm -rf "$WORK"' 0
fail() { echo "FAIL: $*" >&2; exit 1; }

# Run production parsers, not a rewritten example. mksh treats an unescaped | in ${v%%|*}
# as alternation; bash/dash do not. This used to erase the owner name and reset retry records.
eval "$(awk '
    /^read_md_attempt_record\(\) \{/ { capture=1 }
    /^supervise_workers\(\) \{/ { capture=1 }
    capture { print }
    /^}/ { capture=0 }
' "$LOADER")"
MD_MAX_ATTEMPTS=3
printf '%s\n' 'v2:boot:123:456|2|120' > "$WORK/attempt"
read_md_attempt_record "$WORK/attempt" || fail 'valid MD attempt record rejected'
[ "$MD_RECORD_ID" = 'v2:boot:123:456' ] && [ "$MD_RECORD_COUNT" = 2 ] \
    && [ "$MD_RECORD_NEXT" = 120 ] || fail 'MD retry record split incorrectly'
printf '%s\n' 'v2:boot:123:456|2|120|extra' > "$WORK/attempt"
if read_md_attempt_record "$WORK/attempt"; then fail 'malformed MD record accepted'; fi

WORKER_LANES='steering multidisplay'
WORKER_CHILDREN=' steering|11:100:boot multidisplay|12:200:boot'
SUPERVISOR_TOKEN=10:50:boot
worker_token_is_live() {
    case "$1" in 11:100:boot|12:200:boot) return 0 ;; *) return 1 ;; esac
}
worker_token() { return 1; }
# A broken parser attempts to spawn another child; record that rather than launching a loader.
sh() { : > "$WORK/unexpected-child"; }
for round in 1 2 3; do
    supervise_workers
    wait
    [ ! -f "$WORK/unexpected-child" ] || fail 'supervisor duplicates a live worker'
    [ "$WORKER_CHILDREN" = ' steering|11:100:boot multidisplay|12:200:boot' ] \
        || fail 'supervisor lost the recorded child tokens'
done
echo 'PASS: pipe records and live worker reuse on this shell'
