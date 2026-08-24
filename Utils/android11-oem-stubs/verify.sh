#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/scripts/common.sh"

parse_serial_only "$@"
require_api30_emulator

printf '%-39s %-8s %s\n' PACKAGE PID PROCESS
for package_name in "${TARGET_PACKAGES[@]}"; do
    package_is_harness_stub "$package_name" || die "$package_name is not an installed harness stub"

    pid="$(adb_for_device shell pidof "$package_name" | tr -d '\r' | awk '{print $1}')"
    [[ -n "$pid" ]] || die "process is not running: $package_name"
    [[ "$pid" =~ ^[0-9]+$ ]] || die "unexpected pid for $package_name: $pid"

    process_name="$(adb_for_device shell cat "/proc/$pid/cmdline" | tr '\000' '\n' | sed -n '1p' | tr -d '\r')"
    [[ "$process_name" == "$package_name" ]] || {
        die "pid $pid has process '$process_name', expected '$package_name'"
    }

    printf '%-39s %-8s %s\n' "$package_name" "$pid" "$process_name"
done
