#!/usr/bin/env bash

set -euo pipefail

readonly HARNESS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly APP_BUILD="$HARNESS_ROOT/app/build.gradle.kts"
readonly MANIFEST="$HARNESS_ROOT/app/src/main/AndroidManifest.xml"
readonly COMMON="$HARNESS_ROOT/scripts/common.sh"

fail() {
    printf 'static check failed: %s\n' "$*" >&2
    exit 1
}

for script in \
    "$HARNESS_ROOT/build.sh" \
    "$HARNESS_ROOT/install.sh" \
    "$HARNESS_ROOT/start.sh" \
    "$HARNESS_ROOT/verify.sh" \
    "$HARNESS_ROOT/cleanup.sh" \
    "$COMMON" \
    "$HARNESS_ROOT/tests/agent-contract-checks.sh" \
    "$0"; do
    bash -n "$script" || fail "invalid shell syntax in $script"
done

grep -Fq 'compileSdk = 30' "$APP_BUILD" || fail "compileSdk is not API 30"
grep -Fq 'minSdk = 30' "$APP_BUILD" || fail "minSdk is not API 30"
grep -Fq 'targetSdk = 30' "$APP_BUILD" || fail "targetSdk is not API 30"
grep -Fq 'versionName = "1.0-stub"' "$APP_BUILD" || fail "stub version marker is missing"

expected_packages=(
    com.qinggan.app.launcher
    com.qinggan.systemservice
    com.qinggan.app.qgime
    com.qinggan.app.vehiclesetting
    com.qinggan.keymanager.service
)
for package_name in "${expected_packages[@]}"; do
    [[ "$(grep -Fxc "    $package_name" "$COMMON")" -eq 1 ]] || {
        fail "$package_name must occur exactly once in the runtime target array"
    }
    grep -Fq "applicationId = \"$package_name\"" "$APP_BUILD" || {
        fail "$package_name is missing from Gradle flavors"
    }
done

grep -Fq 'android:process="${targetProcess}"' "$MANIFEST" || fail "explicit process placeholder is missing"
grep -Fq 'android:exported="true"' "$MANIFEST" || fail "stub service is not exported"
grep -Fq 'android.permission.FOREGROUND_SERVICE' "$MANIFEST" || fail "foreground service permission is missing"
[[ "$(grep -c '<uses-permission ' "$MANIFEST")" -eq 1 ]] || fail "stub must have exactly one permission"

grep -Fq 'this harness requires API 30' "$COMMON" || fail "API 30 runtime guard is missing"
grep -Fq 'is not an emulator' "$COMMON" || fail "physical-device guard is missing"
grep -Fq 'is not this harness; refusing to replace it' "$COMMON" || fail "package collision guard is missing"
grep -Fq 'Skipping non-harness package' "$HARNESS_ROOT/cleanup.sh" || fail "cleanup ownership guard is missing"

if grep -Eq 'pm[[:space:]]+list|uninstall[^\n]*(\*|--all)' "$HARNESS_ROOT/cleanup.sh"; then
    fail "cleanup contains a broad package operation"
fi

"$HARNESS_ROOT/tests/agent-contract-checks.sh"

printf 'Static checks passed for five Android 11 OEM stubs.\n'
