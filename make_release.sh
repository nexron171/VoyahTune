#!/bin/sh
# Сборка релиза Open Voyah: собирает APK обоих флейворов и раскладывает готовые папки релиза.
#
#   ./make_release.sh 3.2.2              → Releases/v3.2.2 и Releases/v3.2.2-light
#   ./make_release.sh 3.2.2 --full-only  → только Releases/v3.2.2
#   ./make_release.sh 3.2.2 --light-only → только Releases/v3.2.2-light
#   ./make_release.sh 3.2.2 --no-build   → не пересобирать APK, только переразложить файлы
#                                          (APK берутся из уже существующей папки релиза)
#
# Источник всего, кроме APK — Releases/_common/ (см. Releases/_common/README.md).
# Папка релиза остаётся ПЛОСКОЙ: install.sh ищет файлы рядом с собой, его править не нужно.
# Заменяет собой прежние build_full.sh / build_light.sh (там версия была зашита в код).
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
COMMON="$ROOT/Releases/_common"

VERSION=""
DO_FULL=1
DO_LIGHT=1
DO_BUILD=1

for arg in "$@"; do
    case "$arg" in
        --full-only)  DO_LIGHT=0 ;;
        --light-only) DO_FULL=0 ;;
        --no-build)   DO_BUILD=0 ;;
        -h|--help)    sed -n '2,14p' "$0"; exit 0 ;;
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
    echo "Нет $COMMON — общая папка релизов отсутствует." >&2
    exit 1
fi

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
    OUT="$ROOT/Releases/v$VERSION"
    echo ""
    echo "########## FULL → Releases/v$VERSION ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks full "$OUT"; else require_apks "$OUT"; fi

    cp "$COMMON/tools/"*                                    "$OUT/"
    cp "$COMMON/inject/"*.js                                "$OUT/"
    cp "$COMMON/system/"*                                   "$OUT/"
    for f in "$COMMON/installer/full/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done

    echo "FULL готов → $OUT"
fi

# ---------------------------------------------------------------------------------------------
# LIGHT: не-root набор — без инжекта, frida, load.bin и boot-хука. Только Unix-установщик
# (Windows-набор для light исторически не выпускался; если понадобится — добавить в
# _common/installer/light/ install.bat/remove.bat и скопировать tools/).
# ---------------------------------------------------------------------------------------------
if [ "$DO_LIGHT" = 1 ]; then
    OUT="$ROOT/Releases/v$VERSION-light"
    echo ""
    echo "########## LIGHT → Releases/v$VERSION-light ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks light "$OUT"; else require_apks "$OUT"; fi

    cp "$COMMON/system/privapp-permissions-ru.big.town.anative.xml" "$OUT/"
    for f in "$COMMON/installer/light/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done

    echo "LIGHT готов → $OUT"
fi

echo ""
echo "=== Состав релиза ==="
[ "$DO_FULL" = 1 ]  && ls -1 "$ROOT/Releases/v$VERSION"
[ "$DO_LIGHT" = 1 ] && { echo "--- light:"; ls -1 "$ROOT/Releases/v$VERSION-light"; }
echo ""
echo "Не забыть: описание версии в hownews.md."
