#!/bin/sh
# Сборка релиза Open Voyah: собирает APK обоих флейворов и раскладывает готовые папки релиза.
#
#   ./make_release.sh 3.2.2              → Releases/build/v3.2.2{,-light} + Releases/dist/*.zip
#   ./make_release.sh 3.2.2 --full-only  → только full
#   ./make_release.sh 3.2.2 --light-only → только light
#   ./make_release.sh 3.2.2 --no-build   → не пересобирать APK, только переразложить файлы
#                                          (APK берутся из уже существующей папки сборки)
#   ./make_release.sh 3.2.2 --no-zip     → не паковать архивы
#
# Источник всего, кроме APK — Packaging/ (см. Packaging/README.md). Он В GIT.
# Releases/ — ТОЛЬКО вывод и целиком в .gitignore: сборки в Releases/build/, готовые к
# раздаче архивы в Releases/dist/. В репозитории релизы больше не хранятся.
# Папка релиза остаётся ПЛОСКОЙ: install.sh ищет файлы рядом с собой, его править не нужно.
# Заменяет собой прежние build_full.sh / build_light.sh (там версия была зашита в код).
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
COMMON="$ROOT/Packaging"
BUILD="$ROOT/Releases/build"
DIST="$ROOT/Releases/dist"

VERSION=""
DO_FULL=1
DO_LIGHT=1
DO_BUILD=1
DO_ZIP=1

for arg in "$@"; do
    case "$arg" in
        --full-only)  DO_LIGHT=0 ;;
        --light-only) DO_FULL=0 ;;
        --no-build)   DO_BUILD=0 ;;
        --no-zip)     DO_ZIP=0 ;;
        -h|--help)    sed -n '2,16p' "$0"; exit 0 ;;
        -*)           echo "Неизвестный флаг: $arg" >&2; exit 1 ;;
        *)            VERSION="$arg" ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "Не указана версия. Пример: ./make_release.sh 3.2.2" >&2
    exit 1
fi
# Версию принимаем и как «3.2.2», и как «v3.2.2» — нормализуем к виду без префикса.
VERSION="${VERSION#v}"

if [ ! -d "$COMMON" ]; then
    echo "Нет $COMMON — папка-источник комплекта релиза отсутствует." >&2
    exit 1
fi

# Упаковать папку релиза в архив для раздачи. Архив содержит одну папку верхнего уровня
# (VoyahTune-<версия>), чтобы у пользователя при распаковке не разъезжались файлы по Загрузкам.
make_zip() {
    dir="$1"; name="$2"
    [ "$DO_ZIP" = 1 ] || return 0
    command -v zip >/dev/null || { echo "  zip не найден — архив пропущен"; return 0; }
    mkdir -p "$DIST"
    rm -f "$DIST/$name.zip"
    # -q тихо, -r рекурсивно; пакуем ИМЕНЕМ папки, поэтому идём в родителя.
    (cd "$(dirname "$dir")" && zip -qr "$DIST/$name.zip" "$(basename "$dir")")
    echo "  архив → Releases/dist/$name.zip ($(du -h "$DIST/$name.zip" | cut -f1))"
}

# Скопировать файл и проставить версию вместо плейсхолдера @VERSION@ (он есть в шапке установщиков).
copy_stamped() {
    src="$1"; dst="$2"
    sed "s/@VERSION@/$VERSION/g" "$src" > "$dst"
    # Права берём с оригинала: install.sh/remove.sh должны остаться исполняемыми.
    chmod "$(stat -f '%Lp' "$src" 2>/dev/null || stat -c '%a' "$src")" "$dst"
}

# Собрать APK одного флейвора и положить в папку релиза под финальными именами.
# $1 = full|light, $2 = папка релиза
build_apks() {
    flavor="$1"; out="$2"
    # assembleFullRelease / assembleLightRelease — первая буква флейвора в верхнем регистре.
    case "$flavor" in
        full)  task="assembleFullRelease" ;;
        light) task="assembleLightRelease" ;;
    esac

    echo "== Native: $task =="
    (cd "$ROOT/Native" && ./gradlew "$task" -q)
    cp "$ROOT/Native/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$out/native.apk"

    echo "== RestoreMode: $task =="
    (cd "$ROOT/RestoreMode" && ./gradlew "$task" -q)
    cp "$ROOT/RestoreMode/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$out/restore_mode.apk"
}

# Проверка, что в папке релиза лежат APK — при --no-build мы их не собираем, но релиз без них невалиден.
require_apks() {
    out="$1"
    for f in native.apk restore_mode.apk; do
        if [ ! -f "$out/$f" ]; then
            echo "Нет $out/$f — с --no-build APK должны уже лежать в папке релиза." >&2
            exit 1
        fi
    done
}

# ---------------------------------------------------------------------------------------------
# FULL: полный набор — инжект-скрипты, boot-обвязка, frida, Windows-инструменты.
# ---------------------------------------------------------------------------------------------
if [ "$DO_FULL" = 1 ]; then
    OUT="$BUILD/VoyahTune-$VERSION"
    echo ""
    echo "########## FULL → Releases/build/VoyahTune-$VERSION ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks full "$OUT"; else require_apks "$OUT"; fi

    cp "$COMMON/tools/"*                                    "$OUT/"
    cp "$COMMON/inject/"*.js                                "$OUT/"
    cp "$COMMON/system/"*                                   "$OUT/"
    for f in "$COMMON/installer/full/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done

    echo "FULL готов → $OUT"
    make_zip "$OUT" "VoyahTune-$VERSION"
fi

# ---------------------------------------------------------------------------------------------
# LIGHT: не-root набор — без инжекта, frida, load.bin и boot-хука.
# Инструменты берём НЕ целиком: нужен только adb (его требуют .bat на Windows; на Unix .sh
# рассчитывает на системный adb). frida-inject в light не кладём — он весит 53M и здесь не нужен.
# ---------------------------------------------------------------------------------------------
LIGHT_TOOLS="adb.exe AdbWinApi.dll AdbWinUsbApi.dll"

if [ "$DO_LIGHT" = 1 ]; then
    OUT="$BUILD/VoyahTune-$VERSION-light"
    echo ""
    echo "########## LIGHT → Releases/build/VoyahTune-$VERSION-light ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks light "$OUT"; else require_apks "$OUT"; fi

    for t in $LIGHT_TOOLS; do
        [ -f "$COMMON/tools/$t" ] || { echo "Нет $COMMON/tools/$t" >&2; exit 1; }
        cp "$COMMON/tools/$t" "$OUT/"
    done
    cp "$COMMON/system/privapp-permissions-ru.big.town.anative.xml" "$OUT/"
    for f in "$COMMON/installer/light/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done

    echo "LIGHT готов → $OUT"
    make_zip "$OUT" "VoyahTune-$VERSION-light"
fi

echo ""
echo "=== Состав релиза ==="
[ "$DO_FULL" = 1 ]  && ls -1 "$BUILD/VoyahTune-$VERSION"
[ "$DO_LIGHT" = 1 ] && { echo "--- light:"; ls -1 "$BUILD/VoyahTune-$VERSION-light"; }
echo ""
echo "Не забыть: описание версии в hownews.md."
