#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/common.sh
source "$SCRIPT_DIR/scripts/common.sh"

APK_DIR="$HARNESS_ROOT/app/build/outputs/apk"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --serial)
            [[ "$#" -ge 2 ]] || die "--serial requires a value"
            set_device_serial "$2"
            shift 2
            ;;
        --apk-dir)
            [[ "$#" -ge 2 ]] || die "--apk-dir requires a value"
            APK_DIR="$2"
            shift 2
            ;;
        *)
            die "usage: $0 --serial SERIAL [--apk-dir OUTPUTS_APK_DIR]"
            ;;
    esac
done

[[ -n "$DEVICE_SERIAL" ]] || die "--serial is mandatory"
[[ "$APK_DIR" == /* ]] || APK_DIR="$(cd "$APK_DIR" 2>/dev/null && pwd)"
require_api30_emulator

for package_name in "${TARGET_PACKAGES[@]}"; do
    assert_safe_package_target "$package_name"
done

for index in "${!TARGET_PACKAGES[@]}"; do
    flavor="${TARGET_FLAVORS[$index]}"
    package_name="${TARGET_PACKAGES[$index]}"
    apk="$APK_DIR/$flavor/debug/app-$flavor-debug.apk"
    [[ -f "$apk" ]] || die "missing APK for $package_name: $apk"
done

for index in "${!TARGET_PACKAGES[@]}"; do
    flavor="${TARGET_FLAVORS[$index]}"
    package_name="${TARGET_PACKAGES[$index]}"
    apk="$APK_DIR/$flavor/debug/app-$flavor-debug.apk"
    printf 'Installing %s\n' "$package_name"
    adb_for_device install -r -t "$apk" >/dev/null
    package_is_harness_stub "$package_name" || die "$package_name installed without harness marker"
done

printf 'Installed %d OEM stubs on %s\n' "${#TARGET_PACKAGES[@]}" "$DEVICE_SERIAL"
