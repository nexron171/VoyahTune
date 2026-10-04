#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
RELEASE_BUILDER="$ROOT/make_release.sh"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

for remover in \
        "$ROOT/Packaging/pi/installer/device/remove.sh" \
        "$ROOT/Packaging/pi/installer/device/remove.bat" \
        "$ROOT/Packaging/od/installer/device/remove.sh" \
        "$ROOT/Packaging/od/installer/device/remove.bat"; do
    require_fixed "$remover" 'pm uninstall --user 0 ru.big.town.anative'
    require_fixed "$remover" 'pm uninstall ru.big.town.restoremode'
    require_fixed "$remover" 'pm uninstall big.town.runyn'
    require_fixed "$remover" 'pm path big.town.runyn'
    require_fixed "$remover" 'stop-loader-device.sh /data/local/tmp/voyahtune-stop-loader.sh'
    if grep -Fq 'pkill -f /data/local/bin/load.bin' "$remover"; then
        fail "$remover can kill its own adb shell through a substring match"
    fi
    if grep -Eiv '^[[:space:]]*(#|rem[[:space:]])' "$remover" \
            | grep -Eq 'rm -rf.*(/data/user|/data/user_de|/data/data|/data/misc/profiles|/sdcard/Android)'; then
        fail "$remover manually deletes PackageManager-owned Android 11 app data"
    fi
done

for installer in \
        "$ROOT/Packaging/pi/installer/device/install.sh" \
        "$ROOT/Packaging/pi/installer/device/install.bat" \
        "$ROOT/Packaging/od/installer/device/install.sh" \
        "$ROOT/Packaging/od/installer/device/install.bat"; do
    require_fixed "$installer" 'pm uninstall -k --user 0 ru.big.town.anative'
    require_fixed "$installer" 'cmd package install-existing --user 0 --wait ru.big.town.anative'
    require_fixed "$installer" '/data/user/0/ru.big.town.anative'
    require_fixed "$installer" '/data/user_de/0/ru.big.town.anative'
    require_fixed "$installer" 'com.qinggan.intent.QINGGAN_BOOT_COMPLETE'
    require_fixed "$installer" 'pidof ru.big.town.anative'
done

for extension in sh bat; do
    pi_installer="$ROOT/Packaging/pi/installer/device/install.$extension"
    od_installer="$ROOT/Packaging/od/installer/device/install.$extension"
    require_fixed "$pi_installer" 'install -r -g runyn.apk'
    if grep -Eq 'uninstall.*big\.town\.runyn|pm clear.*big\.town\.runyn' "$pi_installer"; then
        fail "$pi_installer must preserve RunYN settings during replacement"
    fi
    if grep -Eq 'runyn\.apk|big\.town\.runyn' "$od_installer"; then
        fail "$od_installer must leave RunYN unchanged during installation"
    fi
done

sh -n "$ROOT/Packaging/od/installer/device/install.sh"
sh -n "$ROOT/Packaging/od/installer/device/remove.sh"
require_fixed "$RELEASE_BUILDER" 'Packaging/tests/test_infrastructure_profiles.py'
require_fixed "$ROOT/Packaging/tests/test_infrastructure_profiles.py" "'test_android11_package_lifecycle.sh'"
require_fixed "$RELEASE_BUILDER" 'sh -n "$out/install.sh"'
require_fixed "$RELEASE_BUILDER" 'sh -n "$out/remove.sh"'

echo "PASS: Android 11 remove/install lifecycle is PackageManager-owned and launch-verified"
