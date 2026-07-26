#!/bin/sh
# Собрать FULL-флейвор обоих проектов (release, debug-подпись) и разложить в Releases/v3.2.
# FULL = вся функциональность: сплит/док/VirtualDisplay + «Кнопки на руле» + не-root логика.
# Frida-обвязку (vd_bypass.js/load.bin/init.logcat.sh/keymng2.js) кладём/обновляем вручную —
# этот скрипт собирает только APK.
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/Releases/v3.2"
mkdir -p "$OUT"

echo "== Native: assembleFullRelease =="
(cd "$ROOT/Native" && ./gradlew assembleFullRelease -q)
cp "$ROOT/Native/app/build/outputs/apk/full/release/app-full-release.apk" "$OUT/native.apk"

echo "== RestoreMode: assembleFullRelease =="
(cd "$ROOT/RestoreMode" && ./gradlew assembleFullRelease -q)
cp "$ROOT/RestoreMode/app/build/outputs/apk/full/release/app-full-release.apk" "$OUT/restore_mode.apk"

echo ""
echo "FULL готов → $OUT"
ls -la "$OUT/native.apk" "$OUT/restore_mode.apk"
