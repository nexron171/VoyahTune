#!/bin/sh
# Build the PI loader from source for the Android 11 ARM64 target.
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SOURCE/../../.." && pwd)
OUTPUT="$ROOT/Releases/build/pi/loaderFrida/arm64-v8a/loaderFrida"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) [ "$#" -ge 2 ] || { echo '--output requires a path' >&2; exit 2; }; OUTPUT=$2; shift 2 ;;
        -h|--help) echo 'build.sh [--output PATH] (GO_BIN, ANDROID_NDK_HOME, ANDROID_SDK_ROOT are optional)'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
case "$OUTPUT" in /*) ;; *) OUTPUT="$(pwd)/$OUTPUT" ;; esac
GO_BIN=${GO_BIN:-$(command -v go || true)}
if [ -z "$GO_BIN" ]; then
    for candidate in "$ROOT"/Releases/cache/go-toolchain/*/go/bin/go /opt/homebrew/bin/go /usr/local/go/bin/go; do
        [ ! -x "$candidate" ] || GO_BIN=$candidate
    done
fi
[ -n "$GO_BIN" ] && [ -x "$GO_BIN" ] || { echo 'Go 1.23+ is required; set GO_BIN to the installed compiler.' >&2; exit 1; }
case "$GO_BIN" in /*) ;; *) GO_BIN="$(pwd)/$GO_BIN" ;; esac
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then
    SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [ -z "$SDK" ]; then
        case "$(uname -s)" in Darwin) SDK="$HOME/Library/Android/sdk" ;; *) SDK="$HOME/Android/Sdk" ;; esac
    fi
    for candidate in "$SDK"/ndk/*; do
        [ ! -d "$candidate/toolchains/llvm" ] || NDK=$candidate
    done
fi
case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux) NDK_HOST=linux-x86_64 ;;
    *) echo 'Build the Android loader on macOS or Linux with an Android NDK.' >&2; exit 1 ;;
esac
CC_BIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/aarch64-linux-android30-clang"
[ -x "$CC_BIN" ] || { echo "Android NDK ARM64 API 30 compiler not found: $CC_BIN" >&2; exit 1; }
mkdir -p "$(dirname -- "$OUTPUT")" "$ROOT/Releases/cache/go-build" "$ROOT/Releases/cache/go-mod"
cd "$SOURCE"
GOTOOLCHAIN=local GOCACHE="${GOCACHE:-$ROOT/Releases/cache/go-build}" GOMODCACHE="${GOMODCACHE:-$ROOT/Releases/cache/go-mod}" \
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_BIN" \
    "$GO_BIN" build -trimpath -buildvcs=false -buildmode=pie -o "$OUTPUT.new" .
chmod 755 "$OUTPUT.new"
mv -f "$OUTPUT.new" "$OUTPUT"
printf '%s\n' "$OUTPUT"
