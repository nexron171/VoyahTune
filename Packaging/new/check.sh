#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$SOURCE/cache/go-build" "$SOURCE/cache/go-mod"
python3 "$SOURCE/scripts/check_java_agents.py"
python3 "$SOURCE/tests/test_native_bundle.py"
python3 "$SOURCE/tests/test_navigation_fonts.py"
python3 "$SOURCE/../tests/test_acc_preferences.py"
python3 "$SOURCE/../tests/test_native_maintenance.py"
python3 "$SOURCE/../tests/test_stop_loader.py"
sh "$SOURCE/../tests/test_apollo_safe_device.sh"
sh -n "$SOURCE/build.sh" "$SOURCE/system/voyahtune.load.sh" "$SOURCE/system/stop-loader.sh"
