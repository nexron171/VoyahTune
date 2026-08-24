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
        "$ROOT/Packaging/installer/full/remove.sh" \
        "$ROOT/Packaging/installer/full/remove.bat" \
        "$ROOT/Packaging/installer/light/remove.sh" \
        "$ROOT/Packaging/installer/light/remove.bat"; do
    require_fixed "$remover" 'pm uninstall --user 0 ru.big.town.anative'
    require_fixed "$remover" 'pm uninstall ru.big.town.restoremode'
    if grep -Eiv '^[[:space:]]*(#|rem[[:space:]])' "$remover" \
            | grep -Eq 'rm -rf.*(/data/user|/data/user_de|/data/data|/data/misc/profiles|/sdcard/Android)'; then
        fail "$remover manually deletes PackageManager-owned Android 11 app data"
    fi
done

for installer in \
        "$ROOT/Packaging/installer/full/install.sh" \
        "$ROOT/Packaging/installer/full/install.bat" \
        "$ROOT/Packaging/installer/light/install.sh" \
        "$ROOT/Packaging/installer/light/install.bat"; do
    require_fixed "$installer" 'pm uninstall -k --user 0 ru.big.town.anative'
    require_fixed "$installer" 'cmd package install-existing --user 0 --wait ru.big.town.anative'
    require_fixed "$installer" '/data/user/0/ru.big.town.anative'
    require_fixed "$installer" '/data/user_de/0/ru.big.town.anative'
    require_fixed "$installer" 'com.qinggan.intent.QINGGAN_BOOT_COMPLETE'
    require_fixed "$installer" 'pidof ru.big.town.anative'
done

sh -n "$ROOT/Packaging/installer/full/install.sh"
sh -n "$ROOT/Packaging/installer/full/remove.sh"
sh -n "$ROOT/Packaging/installer/light/install.sh"
sh -n "$ROOT/Packaging/installer/light/remove.sh"
require_fixed "$RELEASE_BUILDER" 'test_android11_package_lifecycle.sh'
require_fixed "$RELEASE_BUILDER" 'sh -n "$out/install.sh"'
require_fixed "$RELEASE_BUILDER" 'sh -n "$out/remove.sh"'

echo "PASS: Android 11 remove/install lifecycle is PackageManager-owned and launch-verified"
