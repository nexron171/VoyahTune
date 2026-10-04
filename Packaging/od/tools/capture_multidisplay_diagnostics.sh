#!/bin/sh
# Capture a self-contained multi-display failure bundle immediately after reproducing a three-finger
# move or the OEM swap-button failure. Read-only on the head unit: no settings, processes or files are
# changed. Usage:
#   ./Packaging/tools/capture_multidisplay_diagnostics.sh [serial] [output-dir]
set -eu

ADB_BIN=${ADB_BIN:-adb}
SERIAL=${1:-${ANDROID_SERIAL:-}}
STAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR=${2:-"multidisplay-diagnostics-$STAMP"}

adb_run() {
    if [ -n "$SERIAL" ]; then
        "$ADB_BIN" -s "$SERIAL" "$@"
    else
        "$ADB_BIN" "$@"
    fi
}

mkdir -p "$OUT_DIR"
adb_run get-state >/dev/null

{
    printf 'captured_at=%s\n' "$(date -Iseconds 2>/dev/null || date)"
    printf 'serial=%s\n' "${SERIAL:-default-adb-device}"
    printf 'adb=%s\n' "$ADB_BIN"
} > "$OUT_DIR/metadata.txt"

adb_run shell getprop > "$OUT_DIR/getprop.txt" 2>&1 || true
adb_run shell ps -A -ef > "$OUT_DIR/processes.txt" 2>&1 || true
adb_run shell dumpsys activity activities > "$OUT_DIR/dumpsys-activity.txt" 2>&1 || true
adb_run shell dumpsys window windows > "$OUT_DIR/dumpsys-window.txt" 2>&1 || true
adb_run shell dumpsys display > "$OUT_DIR/dumpsys-display.txt" 2>&1 || true
adb_run shell dumpsys activity services com.qinggan.systemservice > "$OUT_DIR/dumpsys-systemservice.txt" 2>&1 || true
adb_run shell logcat -b all -d -v threadtime > "$OUT_DIR/logcat-all.txt" 2>&1 || true

adb_run shell settings get global voyahtune_multidisplay > "$OUT_DIR/setting-multidisplay.txt" 2>&1 || true
adb_run shell settings get global voyahtune_screen_lift_type > "$OUT_DIR/setting-screen-lift.txt" 2>&1 || true
adb_run shell settings get global voyahtune_freeform > "$OUT_DIR/setting-freeform.txt" 2>&1 || true

for remote in \
        /data/local/tmp/voyahtune-hook-status.v1 \
        /data/local/tmp/voyahtune_md.txt \
        /data/local/tmp/voyahtune_md.txt.try \
        /data/local/tmp/voyahtune_md.pid \
        /data/local/tmp/voyahtune_md.attempt \
        /data/local/tmp/voyahtune_load.txt \
        /system/etc/init/voyahtune.load.rc; do
    adb_run pull "$remote" "$OUT_DIR/" >/dev/null 2>&1 || true
done

printf 'Multi-display diagnostics saved to %s\n' "$OUT_DIR"
