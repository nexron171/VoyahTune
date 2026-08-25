#!/usr/bin/env bash

set -euo pipefail

readonly HARNESS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$HARNESS_ROOT/../.." && pwd)"
readonly GRADLE_WRAPPER="$REPOSITORY_ROOT/Native/gradlew"
readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"

[[ -x "$GRADLE_WRAPPER" ]] || {
    printf 'error: Gradle wrapper not found: %s\n' "$GRADLE_WRAPPER" >&2
    exit 1
}
[[ -f "$SDK_ROOT/platforms/android-30/android.jar" ]] || {
    printf 'error: Android SDK platform 30 not found under %s\n' "$SDK_ROOT" >&2
    exit 1
}

exec "$GRADLE_WRAPPER" \
    --offline \
    --no-daemon \
    -p "$HARNESS_ROOT" \
    :app:assembleDebug
