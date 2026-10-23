#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GO_BIN=${GO_BIN:-$(command -v go)}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK" ]; then
    case "$(uname -s)" in Darwin) SDK="$HOME/Library/Android/sdk" ;; *) SDK="$HOME/Android/Sdk" ;; esac
fi
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then
    for candidate in "$SDK"/ndk/*; do
        [ ! -d "$candidate/toolchains/llvm" ] || NDK=$candidate
    done
fi
case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux) NDK_HOST=linux-x86_64 ;;
    *) echo 'Android build requires macOS or Linux.' >&2; exit 1 ;;
esac
CC_BIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/aarch64-linux-android30-clang"
[ -x "$CC_BIN" ] || { echo "Missing Android API 30 compiler: $CC_BIN" >&2; exit 1; }
mkdir -p "$SOURCE/build/runtime" "$SOURCE/build/system" "$SOURCE/cache/go-build" "$SOURCE/cache/go-mod"
cd "$SOURCE/loader"
GOTOOLCHAIN=local GOPROXY=off GOCACHE="$SOURCE/cache/go-build" GOMODCACHE="$SOURCE/cache/go-mod" \
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_BIN" \
    "$GO_BIN" build -trimpath -buildvcs=false -buildmode=pie -o "$SOURCE/build/runtime/voyahtune-loader.new" .
chmod 755 "$SOURCE/build/runtime/voyahtune-loader.new"
mv -f "$SOURCE/build/runtime/voyahtune-loader.new" "$SOURCE/build/runtime/voyahtune-loader"
python3 "$SOURCE/scripts/bundle.py"
cp "$SOURCE/system/"* "$SOURCE/build/system/"
cp "$SOURCE/config/install-layout.json" "$SOURCE/build/install-layout.json"
printf '%s\n' "$SOURCE/build/runtime"
