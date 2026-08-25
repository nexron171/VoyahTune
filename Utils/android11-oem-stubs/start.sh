#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/scripts/common.sh"

parse_serial_only "$@"
require_api30_emulator

for package_name in "${TARGET_PACKAGES[@]}"; do
    package_is_harness_stub "$package_name" || die "$package_name is not an installed harness stub"
    component="$package_name/$STUB_SERVICE_CLASS"
    printf 'Starting %s\n' "$package_name"
    adb_for_device shell am start-foreground-service -n "$component" >/dev/null
done

exec "$HARNESS_ROOT/verify.sh" --serial "$DEVICE_SERIAL"
