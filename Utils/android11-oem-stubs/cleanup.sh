#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/scripts/common.sh"

parse_serial_only "$@"
require_api30_emulator

for package_name in "${TARGET_PACKAGES[@]}"; do
    if ! package_is_installed "$package_name"; then
        printf 'Already absent: %s\n' "$package_name"
        continue
    fi
    if ! package_is_harness_stub "$package_name"; then
        printf 'Skipping non-harness package: %s\n' "$package_name" >&2
        continue
    fi

    printf 'Removing %s\n' "$package_name"
    adb_for_device shell am force-stop "$package_name" >/dev/null
    adb_for_device uninstall "$package_name" >/dev/null
done
