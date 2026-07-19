#!/bin/sh
# Собрать LIGHT-флейвор обоих проектов (release, debug-подпись) и разложить в Releases/v3.1-light.
# LIGHT = только не-root функциональность (без сплита/дока/VirtualDisplay/Frida/«Кнопок на руле»).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/Releases/v3.1-light"
mkdir -p "$OUT"

echo "== Native: assembleLightRelease =="
(cd "$ROOT/Native" && ./gradlew assembleLightRelease -q)
cp "$ROOT/Native/app/build/outputs/apk/light/release/app-light-release.apk" "$OUT/native.apk"

echo "== RestoreMode: assembleLightRelease =="
(cd "$ROOT/RestoreMode" && ./gradlew assembleLightRelease -q)
cp "$ROOT/RestoreMode/app/build/outputs/apk/light/release/app-light-release.apk" "$OUT/restore_mode.apk"

echo ""
echo "LIGHT готов → $OUT"
ls -la "$OUT/native.apk" "$OUT/restore_mode.apk"
