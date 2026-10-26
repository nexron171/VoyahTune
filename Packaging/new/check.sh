#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$SOURCE/cache/go-build" "$SOURCE/cache/go-mod"
for test_file in "$SOURCE"/tests/*.test.js; do node "$test_file"; done
python3 "$SOURCE/../tests/test_acc_preferences.py"
python3 "$SOURCE/../tests/test_stop_loader.py"
sh "$SOURCE/../tests/test_apollo_safe_device.sh"
cd "$SOURCE/loader"
GOTOOLCHAIN=local GOPROXY=off GOCACHE="$SOURCE/cache/go-build" GOMODCACHE="$SOURCE/cache/go-mod" \
    go test ./...
sh -n "$SOURCE/build.sh" "$SOURCE/system/voyahtune.load.sh" "$SOURCE/system/stop-loader.sh"
