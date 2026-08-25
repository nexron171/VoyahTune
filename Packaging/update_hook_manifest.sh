#!/bin/sh
# Regenerates the tracked hook manifest from the exact Frida sources.
# The output is staged in the same directory and atomically renamed so a failed generation cannot
# leave a truncated manifest. Run this after changing any injected script, then review the diff.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
INJECT="$ROOT/Packaging/inject"
TARGET="$ROOT/Packaging/system/voyahtune-hook-manifest.json"
STAGE="$TARGET.$$.new"

cleanup() { rm -f "$STAGE"; }
trap cleanup 0
trap 'exit 1' HUP INT TERM

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "Neither sha256sum nor shasum is available" >&2
        return 1
    fi
}

manifest_entry() {
    id=$1 process=$2 script=$3 suffix=$4
    hash=$(sha256_file "$INJECT/$script")
    printf '    {"id":"%s","process":"%s","script":"%s","sha256":"%s"}%s\n' \
        "$id" "$process" "$script" "$hash" "$suffix"
}

{
    printf '{\n  "schemaVersion": 1,\n  "hooks": [\n'
    manifest_entry vd-bypass system_server vd_bypass.js ,
    manifest_entry steering-wheel com.qinggan.keymanager.service steeringwheelkeys.js ,
    manifest_entry launcher-dock com.qinggan.app.launcher launcherdock.js ,
    manifest_entry multi-display com.qinggan.systemservice multidisplay.js ,
    manifest_entry apollo-tech com.qinggan.app.vehiclesetting apollo_tech.js ,
    manifest_entry keyboard-en com.qinggan.app.qgime keyboard_lock_en.js ,
    manifest_entry keyboard-ru com.qinggan.app.qgime keyboard_ru.js ''
    printf '  ]\n}\n'
} > "$STAGE"

chmod 0644 "$STAGE"
mv -f "$STAGE" "$TARGET"
trap - 0 HUP INT TERM
echo "Updated $TARGET"
