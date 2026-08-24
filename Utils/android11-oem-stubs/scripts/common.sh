#!/usr/bin/env bash

set -euo pipefail

readonly HARNESS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STUB_VERSION_NAME="1.0-stub"
readonly STUB_SERVICE_CLASS="qa.voyahtune.oemstub.StubService"
readonly TARGET_FLAVORS=(
    launcher
    systemservice
    qgime
    vehiclesetting
    keymanager
)
readonly TARGET_PACKAGES=(
    com.qinggan.app.launcher
    com.qinggan.systemservice
    com.qinggan.app.qgime
    com.qinggan.app.vehiclesetting
    com.qinggan.keymanager.service
)

ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL=""

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

set_device_serial() {
    local serial="$1"
    [[ -n "$serial" ]] || die "--serial requires a non-empty value"
    [[ "$serial" != -* ]] || die "invalid adb serial: $serial"
    DEVICE_SERIAL="$serial"
}

adb_for_device() {
    [[ -n "$DEVICE_SERIAL" ]] || die "internal error: device serial was not set"
    "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
}

require_api30_emulator() {
    local sdk qemu kernel_qemu

    require_command "$ADB_BIN"
    adb_for_device get-state >/dev/null

    sdk="$(adb_for_device shell getprop ro.build.version.sdk | tr -d '\r')"
    [[ "$sdk" == "30" ]] || die "device $DEVICE_SERIAL is API $sdk; this harness requires API 30"

    qemu="$(adb_for_device shell getprop ro.boot.qemu | tr -d '\r')"
    kernel_qemu="$(adb_for_device shell getprop ro.kernel.qemu | tr -d '\r')"
    if [[ "$qemu" != "1" && "$kernel_qemu" != "1" ]]; then
        die "device $DEVICE_SERIAL is not an emulator; refusing OEM package collision risk"
    fi
}

package_path() {
    local package_name="$1"
    adb_for_device shell pm path "$package_name" 2>/dev/null | tr -d '\r'
}

package_is_installed() {
    local package_name="$1"
    [[ "$(package_path "$package_name")" == package:* ]]
}

package_is_harness_stub() {
    local package_name="$1"
    local dump
    dump="$(adb_for_device shell dumpsys package "$package_name" 2>/dev/null || true)"
    grep -Fq "versionName=$STUB_VERSION_NAME" <<<"$dump"
}

assert_safe_package_target() {
    local package_name="$1"
    if package_is_installed "$package_name" && ! package_is_harness_stub "$package_name"; then
        die "package $package_name already exists and is not this harness; refusing to replace it"
    fi
}

parse_serial_only() {
    if [[ "$#" -ne 2 || "$1" != "--serial" ]]; then
        die "usage: $0 --serial SERIAL"
    fi
    set_device_serial "$2"
}
